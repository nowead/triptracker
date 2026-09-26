package com.triptracker.app.data

import androidx.room.Room
import androidx.room.withTransaction
import android.database.sqlite.SQLiteConstraintException
import com.triptracker.app.data.local.BreakerEntity
import com.triptracker.app.data.local.PeriodEntity
import com.triptracker.app.data.local.TripDatabase
import com.triptracker.app.data.local.TripEntity
import com.triptracker.app.data.repository.RoomTripRepository
import com.triptracker.app.domain.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TripRepositoryTest {
    private lateinit var db: TripDatabase
    private lateinit var repository: RoomTripRepository
    private val day = LocalDate.of(2026, 9, 15)
    private val clock = Clock.fixed(Instant.parse("2026-09-15T01:00:00Z"), ZoneId.of("Asia/Seoul"))

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), TripDatabase::class.java).build()
        repository = RoomTripRepository(db, clock)
    }
    @After fun tearDown() = db.close()

    private suspend fun period(
        building: Int = 1, floor: String = "3", panel: String = "LP-3-A", name: String = "R1",
        date: LocalDate? = null,
    ): Long {
        val breaker = db.dao().insertBreaker(BreakerEntity(building = building, floor = floor, panelNumber = panel, breakerName = name))
        val period = db.dao().insertPeriod(PeriodEntity(breakerId = breaker, sequence = 1, replacementDay = date?.toEpochDay()))
        db.dao().setCurrentPeriod(breaker, period)
        return period
    }
    private fun input(period: Long, date: LocalDate = day, location: String = " 사무실 a ") =
        NewTrip(periodId = period, tripDate = date, location = location, note = "Mixed Case\n 비고 ")

    @Test fun sameDayTripsRemainTwoDistinctEvents() = runTest {
        val p = period()
        val first = repository.addTrip(input(p))
        val second = repository.addTrip(input(p))
        assertNotEquals(first, second)
        val rows = repository.observeTrips().first()
        assertEquals(listOf(second, first), rows.map { it.id })
        assertEquals("사무실 A", rows.first().location)
        assertEquals("Mixed Case\n 비고 ", rows.first().note)
        assertNull(rows.first().replacementDate)
        assertEquals(2L, repository.observeCounts().first().single().tripCount)
    }

    @Test fun emptyCurrentPeriodAndPreviousHistoryRemainSeparate() = runTest {
        val old = period()
        repository.addTrip(input(old, day.minusDays(1)))
        val b = db.dao().getPeriod(old)!!.breakerId
        val current = db.dao().insertPeriod(PeriodEntity(breakerId = b, sequence = 2, replacementDay = day.toEpochDay()))
        db.dao().setCurrentPeriod(b, current)
        val row = repository.observeCounts().first().single()
        assertEquals(current, row.periodId)
        assertEquals(0L, row.tripCount)
        assertNull(row.location)
        repository.addTrip(input(old, day))
        assertEquals(2L, repository.observeCounts(scope = PeriodScope.PREVIOUS).first().single().tripCount)
        assertEquals(0L, repository.observeCounts().first().single().tripCount)
        assertEquals(2, repository.observeCounts(scope = PeriodScope.ALL).first().size)
    }

    @Test fun searchUsesOptionalAndConditionsAndLiteralSpecialCharacters() = runTest {
        val a = period(panel = "LP%_A", name = "R10")
        val b = period(building = 2, panel = "LPXXA")
        repository.addTrip(input(a))
        repository.addTrip(input(b, day.minusDays(1)))
        assertEquals(2, repository.observeTrips(TripSearch(breakerName = "r1")).first().size)
        assertEquals(1, repository.observeTrips(TripSearch(panelNumber = "%_")).first().size)
        assertTrue(repository.observeTrips(TripSearch(building = 2, panelNumber = "%_")).first().isEmpty())
        assertEquals(1, repository.observeTrips(TripSearch(tripDate = day)).first().size)
        assertEquals(1, repository.observeTrips(TripSearch(building = 1, floor = "3", panelNumber = " lp ")).first().size)
        assertEquals(a, repository.observeCounts(TripSearch(panelNumber = "%_", breakerName = "r1")).first().single().periodId)
        assertTrue(repository.observeCounts(TripSearch(building = 2, panelNumber = "%_")).first().isEmpty())
    }

    @Test fun representativeLocationUsesTripDayThenCreationOrder() = runTest {
        val p = period()
        repository.addTrip(input(p, day, "FIRST"))
        repository.addTrip(input(p, day.minusDays(1), "OLDER"))
        repository.addTrip(input(p, day, "LATEST"))
        assertEquals("LATEST", repository.observeCounts().first().single().location)
    }

    @Test fun countTiesUsePhysicalFloorOrderAndUnknownPeriodsStaySeparate() = runTest {
        for (floor in listOf("11", "2", "PH", "B1", "B5")) period(floor = floor)
        assertEquals(listOf("B5", "B1", "2", "11", "PH"), repository.observeCounts().first().map { it.breaker.floor })
    }

    @Test fun invalidDateOrMissingParentDoesNotWriteATrip() = runTest {
        val p = period(date = day.minusDays(2))
        for (draft in listOf(input(p, day.plusDays(1)), input(p, day.minusDays(3)), input(p, location = "  "), input(999))) {
            try { repository.addTrip(draft); fail("Expected invalid trip rejection") } catch (_: IllegalArgumentException) { }
        }
        assertTrue(repository.observeTrips().first().isEmpty())
    }

    @Test fun previousPeriodRejectsTripAfterNextReplacement() = runTest {
        val old = period()
        val b = db.dao().getPeriod(old)!!.breakerId
        val current = db.dao().insertPeriod(PeriodEntity(breakerId = b, sequence = 2, replacementDay = day.minusDays(1).toEpochDay()))
        db.dao().setCurrentPeriod(b, current)
        try { repository.addTrip(input(old)); fail("Expected boundary rejection") } catch (_: IllegalArgumentException) { }
        assertTrue(repository.observeTrips().first().isEmpty())
    }

    @Test fun periodsFindUsesExactNormalizedIdentityAndCurrentFirst() = runTest {
        val old = period(name = "R10")
        val b = db.dao().getPeriod(old)!!.breakerId
        val current = db.dao().insertPeriod(PeriodEntity(breakerId = b, sequence = 2, replacementDay = day.toEpochDay()))
        db.dao().setCurrentPeriod(b, current)
        assertTrue(repository.findPeriods(BreakerKey(1, "3", "LP-3-A", "R1")).isEmpty())
        val periods = repository.findPeriods(BreakerKey(1, "3", " lp-3-a ", "r10"))
        assertEquals(listOf(current, old), periods.map { it.id })
        assertTrue(periods.first().isCurrent)
        assertFalse(periods.last().isCurrent)
    }

    @Test fun flowEmitsNewCountAfterInsert() = runTest {
        val p = period()
        val initial = kotlinx.coroutines.CompletableDeferred<Unit>()
        val updated = kotlinx.coroutines.CompletableDeferred<Long>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            repository.observeCounts().collect { rows ->
                if (rows.single().tripCount == 0L) initial.complete(Unit)
                if (rows.single().tripCount == 1L) updated.complete(rows.single().tripCount)
            }
        }
        initial.await()
        repository.addTrip(input(p))
        assertEquals(1L, updated.await())
    }

    @Test fun foreignKeysRejectOrphansAndDeletingReferencedPeriod() = runTest {
        try {
            db.dao().insertTrip(TripEntity(periodId = 999, tripDay = day.toEpochDay(), location = "A", note = "", createdAt = 0))
            fail("Expected foreign key rejection")
        } catch (_: SQLiteConstraintException) { }
        val p = period()
        repository.addTrip(input(p))
        try {
            db.openHelper.writableDatabase.execSQL("DELETE FROM replacement_period WHERE id = ?", arrayOf(p))
            fail("Expected delete restriction")
        } catch (_: SQLiteConstraintException) { }
        assertEquals(1L, repository.observeCounts().first().single().tripCount)
    }

    @Test fun currentPeriodCannotReferToAnotherBreaker() = runTest {
        val a = period()
        val b = period(building = 2)
        assertEquals(0, db.dao().setCurrentPeriod(db.dao().getPeriod(a)!!.breakerId, b))
        assertEquals(setOf(a, b), repository.observeCounts().first().map { it.periodId }.toSet())
    }

    @Test fun failedTransactionLeavesNoPartialBreakerOrPeriod() = runTest {
        try {
            db.withTransaction {
                period()
                error("Simulated failure")
            }
            fail("Expected rollback")
        } catch (_: IllegalStateException) { }
        assertNull(db.dao().findBreaker(1, "3", "LP-3-A", "R1"))
        assertTrue(repository.observeCounts(scope = PeriodScope.ALL).first().isEmpty())
    }

    @Test fun deletingLatestIdDoesNotReuseItForAnotherEvent() = runTest {
        val p = period()
        val deleted = repository.addTrip(input(p))
        db.openHelper.writableDatabase.execSQL("DELETE FROM trip_event WHERE id = ?", arrayOf(deleted))
        assertTrue(repository.addTrip(input(p)) > deleted)
    }

    @Test fun fileDatabaseRetainsTripsAfterCloseAndReopen() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val name = "persistence-test.db"
        context.deleteDatabase(name)
        db.close()
        try {
            db = Room.databaseBuilder(context, TripDatabase::class.java, name).build()
            repository = RoomTripRepository(db, clock)
            val saved = repository.addTrip(input(period()))
            db.close()
            db = Room.databaseBuilder(context, TripDatabase::class.java, name).build()
            repository = RoomTripRepository(db, clock)
            assertEquals(saved, repository.observeTrips().first().single().id)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun countsSortHighestFirstIncludingZero() = runTest {
        val zero = period(building = 1)
        val one = period(building = 2)
        val two = period(building = 3)
        repository.addTrip(input(one))
        repeat(2) { repository.addTrip(input(two)) }
        assertEquals(listOf(two, one, zero), repository.observeCounts().first().map { it.periodId })
    }

    @Test fun duplicateBreakerAndPeriodSequenceAreRejected() = runTest {
        val p = period()
        try {
            db.dao().insertBreaker(BreakerEntity(building = 1, floor = "3", panelNumber = "LP-3-A", breakerName = "R1"))
            fail("Expected duplicate identity rejection")
        } catch (_: SQLiteConstraintException) { }
        try {
            db.dao().insertPeriod(PeriodEntity(breakerId = db.dao().getPeriod(p)!!.breakerId, sequence = 1, replacementDay = null))
            fail("Expected duplicate sequence rejection")
        } catch (_: SQLiteConstraintException) { }
        assertEquals(1, repository.observeCounts(scope = PeriodScope.ALL).first().size)
    }

    @Test fun updateOnlySelectedDuplicateAndPreservesIdentityAndTimestamp() = runTest {
        val a = repository.registerTrip(registration())
        val p = repository.findPeriods(registration().breaker).single().id
        val b = repository.registerTrip(registration("b").copy(periodId = p))
        val before = repository.observeTrips().first()
        repository.updateTrip(a, registration("edit").copy(periodId = p, location = "new place", note = "Case\n 유지"))
        val after = repository.observeTrips().first()
        assertEquals(2, after.size)
        assertEquals(before.first { it.id == b }, after.first { it.id == b })
        assertEquals(before.first { it.id == a }.createdAt, after.first { it.id == a }.createdAt)
        assertEquals("NEW PLACE", after.first { it.id == a }.location)
        assertEquals("Case\n 유지", after.first { it.id == a }.note)
        assertEquals(2L, repository.observeCounts().first().single().tripCount)
    }

    @Test fun movingTripChangesOnlySourceAndDestinationCounts() = runTest {
        val a = repository.registerTrip(registration())
        val source = repository.findPeriods(registration().breaker).single().id
        val target = period(building = 2)
        repository.updateTrip(a, registration().copy(breaker = BreakerKey(2, "3", "LP-3-A", "R1"), periodId = target))
        val counts = repository.observeCounts().first()
        assertEquals(0L, counts.first { it.periodId == source }.tripCount)
        assertEquals(1L, counts.first { it.periodId == target }.tripCount)
        assertEquals(a, repository.observeTrips().first().single().id)
    }

    @Test fun editingToNewBreakerUsesFirstPeriodFlowAndDoesNotRenameSharedBreaker() = runTest {
        val a = repository.registerTrip(registration())
        val p = repository.findPeriods(registration().breaker).single().id
        val b = repository.registerTrip(registration("b").copy(periodId = p))
        repository.updateTrip(a, registration().copy(breaker = BreakerKey(1, "3", "NEW", "R2")))
        val rows = repository.observeTrips().first()
        assertEquals("NEW", rows.first { it.id == a }.breaker.panelNumber)
        assertEquals("LP-3-A", rows.first { it.id == b }.breaker.panelNumber)
        assertEquals(2, rows.size)
    }

    @Test fun invalidEditAndMissingTripLeaveDataUnchanged() = runTest {
        val id = repository.registerTrip(registration())
        val p = repository.findPeriods(registration().breaker).single().id
        val before = repository.observeTrips().first()
        try { repository.updateTrip(id, registration().copy(periodId = p, tripDate = day.plusDays(1))); fail("Expected date rejection") }
        catch (_: IllegalArgumentException) { }
        try { repository.updateTrip(999, registration().copy(breaker = BreakerKey(1, "3", "NEW", "R2"))); fail("Expected missing ID rejection") }
        catch (_: IllegalArgumentException) { }
        assertEquals(before, repository.observeTrips().first())
        assertNull(db.dao().findBreaker(1, "3", "NEW", "R2"))
    }

    @Test fun updateFailureRollsBackNewTargetAndRetainsOriginalTrip() = runTest {
        val id = repository.registerTrip(registration())
        val before = repository.observeTrips().first()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_update BEFORE UPDATE ON trip_event BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        try { repository.updateTrip(id, registration().copy(breaker = BreakerKey(1, "3", "NEW", "R2"))); fail("Expected rollback") }
        catch (_: SQLiteConstraintException) { }
        assertEquals(before, repository.observeTrips().first())
        assertNull(db.dao().findBreaker(1, "3", "NEW", "R2"))
    }

    @Test fun deleteSelectedIdsOnlyAndKeepEmptyPeriodAtZero() = runTest {
        val a = repository.registerTrip(registration())
        val p = repository.findPeriods(registration().breaker).single().id
        val b = repository.registerTrip(registration("b").copy(periodId = p))
        val c = repository.registerTrip(registration("c").copy(periodId = p))
        assertEquals(2, repository.deleteTrips(setOf(a, c)))
        assertEquals(listOf(b), repository.observeTrips().first().map { it.id })
        assertEquals(1L, repository.observeCounts().first().single().tripCount)
        assertEquals(0, repository.deleteTrips(setOf(a, c)))
        repository.deleteTrips(setOf(b))
        assertEquals(0L, repository.observeCounts().first().single().tripCount)
        assertNull(repository.observeCounts().first().single().location)
        assertEquals(0, repository.deleteTrips(emptySet()))
    }

    @Test fun failedBatchDeleteRollsBackEverySelectedRow() = runTest {
        val a = repository.registerTrip(registration())
        val p = repository.findPeriods(registration().breaker).single().id
        val b = repository.registerTrip(registration("b").copy(periodId = p))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_delete BEFORE DELETE ON trip_event WHEN OLD.id = $b BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        try { repository.deleteTrips(setOf(a, b)); fail("Expected rollback") } catch (_: SQLiteConstraintException) { }
        assertEquals(2L, repository.observeCounts().first().single().tripCount)
    }

    private fun registration(token: String = "first") = TripRegistration(
        breaker = BreakerKey(1, "3", " lp-3-a ", "r1"),
        tripDate = day, location = " 사무실 a ", note = "note\n 원문 ",
        replacementUnknown = true, requestId = token,
    )

    @Test fun firstRegistrationCreatesBreakerPeriodAndOneTripTogether() = runTest {
        val id = repository.registerTrip(registration())
        val row = repository.observeTrips().first().single()
        assertEquals(id, row.id)
        assertEquals(BreakerKey(1, "3", "LP-3-A", "R1"), row.breaker)
        assertEquals("사무실 A", row.location)
        assertEquals("note\n 원문 ", row.note)
        assertNull(row.replacementDate)
        assertEquals(1L, repository.observeCounts().first().single().tripCount)
    }

    @Test fun subsequentTripUsesExistingPeriodAndNewId() = runTest {
        val first = repository.registerTrip(registration())
        val p = repository.findPeriods(registration().breaker).single().id
        val second = repository.registerTrip(registration("second").copy(periodId = p, replacementUnknown = false))
        assertNotEquals(first, second)
        assertEquals(1, repository.findPeriods(registration().breaker).size)
        assertEquals(2L, repository.observeCounts().first().single().tripCount)
    }

    @Test fun sameRequestRetryReturnsSavedIdWithoutAnotherTrip() = runTest {
        val request = registration().copy(replacementDate = day.minusDays(10), replacementUnknown = false)
        val id = repository.registerTrip(request)
        assertEquals(id, repository.registerTrip(request))
        assertEquals(1L, repository.observeCounts().first().single().tripCount)
        assertEquals(day.minusDays(10), repository.observeTrips().first().single().replacementDate)
        try { repository.registerTrip(request.copy(location = "OTHER")); fail("Expected changed request rejection") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun invalidFirstRegistrationLeavesNoPartialData() = runTest {
        val invalid = listOf(
            registration().copy(replacementUnknown = false),
            registration().copy(replacementUnknown = false, replacementDate = day.plusDays(1)),
            registration().copy(replacementUnknown = false, replacementDate = day, tripDate = day.minusDays(1)),
            registration().copy(location = " "), registration().copy(breaker = BreakerKey(5, "3", "LP", "R")),
            registration().copy(breaker = BreakerKey(1, "0", "LP", "R")),
        )
        for (request in invalid) {
            try { repository.registerTrip(request); fail("Expected validation failure") }
            catch (_: IllegalArgumentException) { }
            assertTrue(repository.observeTrips().first().isEmpty())
            assertNull(db.dao().findBreaker(1, "3", "LP-3-A", "R1"))
        }
    }

    @Test fun databaseFailureDuringFirstTripRollsBackNewBreakerAndPeriod() = runTest {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_trip BEFORE INSERT ON trip_event BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        try { repository.registerTrip(registration()); fail("Expected insert failure") }
        catch (_: SQLiteConstraintException) { }
        assertNull(db.dao().findBreaker(1, "3", "LP-3-A", "R1"))
        assertTrue(repository.observeCounts(scope = PeriodScope.ALL).first().isEmpty())
    }

    @Test fun existingBreakerCannotReceiveTripInAnotherBreakersPeriod() = runTest {
        val a = period()
        val b = period(building = 2)
        try { repository.registerTrip(registration().copy(periodId = b)); fail("Expected ownership rejection") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0L, repository.observeCounts().first().first { it.periodId == a }.tripCount)
    }
}
