package com.triptracker.app.data

import androidx.room.Room
import com.triptracker.app.data.local.TripDatabase
import com.triptracker.app.data.repository.RoomTripRepository
import com.triptracker.app.domain.*
import java.time.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MasterRepositoryTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), TripDatabase::class.java).build()
    private val today = LocalDate.of(2026, 9, 27)
    private val repo = RoomTripRepository(db, Clock.fixed(Instant.parse("2026-09-27T01:00:00Z"), ZoneId.of("Asia/Seoul")))
    @After fun close() = db.close()
    private suspend fun panel() = repo.savePanel(PanelMaster(building = 1, floor = "3", number = " lp-3 ", location = " 복도 "))
    private fun breaker(panel: Long) = BreakerMaster(panelId = panel, number = "r1", kind = "MCCB", ports = 3,
        ratedAmps = "30.5", load = "사무실 A%_", installationDate = today.minusDays(10), installationUnknown = false)

    @Test fun buildingOneUpperFloorsPersistAndSortBeforePenthouseInPanelsAndCounts() = runTest {
        for (floor in listOf("PH", "13", "11", "12")) {
            val p = repo.savePanel(PanelMaster(building = 1, floor = floor, number = "LP", location = "EPS"))
            repo.saveBreaker(breaker(p))
        }
        assertEquals(listOf("11", "12", "13", "PH"), repo.observePanels().first().map { it.floor })
        assertEquals(listOf("11", "12", "13", "PH"), repo.observeCounts().first().map { it.breaker.floor })
        for (building in 2..4) {
            for (floor in listOf("12", "13")) {
                try {
                    repo.savePanel(PanelMaster(building = building, floor = floor, number = "LP", location = "EPS"))
                    fail("upper floors are confirmed only for building one")
                } catch (_: IllegalArgumentException) { }
            }
        }
        assertEquals(4, repo.observePanels().first().size)
    }

    @Test fun mastersExistWithoutTripsAndLocationSearchUsesLiteralSubstring() = runTest {
        val p = panel()
        repo.saveBreaker(breaker(p))
        assertEquals(0L, repo.observeCounts().first().single().tripCount)
        val row = repo.observeLocations("a%_").first().single()
        assertEquals("LP-3", row.panel.number)
        assertEquals("복도", row.panel.location)
        assertEquals("R1", row.breaker.number)
        assertTrue(repo.observeLocations("A__").first().isEmpty())
    }

    @Test fun parentDeletionIsRestrictedAndEmptyBreakerCanBeDeleted() = runTest {
        val p = panel(); val b = repo.saveBreaker(breaker(p))
        try { repo.deletePanel(p); fail("child must protect panel") } catch (_: IllegalArgumentException) { }
        val count = repo.observeCounts().first().single()
        val id = repo.registerTrip(TripRegistration(count.breaker, today, "현장", periodId = count.periodId,
            requestId = "trip", reason = "과부하", note = "기존 특기사항"))
        try { repo.deleteBreaker(b); fail("history must protect breaker") } catch (_: IllegalArgumentException) { }
        assertEquals("기존 특기사항", repo.observeTrips().first().single().note)
        assertEquals("과부하", repo.observeTrips().first().single().reason)
        repo.deleteTrips(setOf(id)); repo.deleteBreaker(b); repo.deletePanel(p)
        assertTrue(repo.observePanels().first().isEmpty())
    }

    @Test fun replacementStartsAtZeroPreservesHistoryAndDateCorrectionDoesNotReset() = runTest {
        val b = repo.saveBreaker(breaker(panel()))
        val original = repo.observeCounts().first().single()
        repo.registerTrip(TripRegistration(original.breaker, today.minusDays(1), "현장", periodId = original.periodId,
            requestId = "one", reason = "과부하"))
        val next = repo.replaceBreaker(b, today)
        assertEquals(0L, repo.observeCounts().first().single().tripCount)
        assertEquals(1L, repo.observeCounts(scope = PeriodScope.PREVIOUS).first().single().tripCount)
        repo.correctInstallation(original.periodId, today.minusDays(8))
        assertEquals(1L, repo.observeCounts(scope = PeriodScope.PREVIOUS).first().single().tripCount)
        try { repo.correctInstallation(next, today.minusDays(2)); fail("would contradict prior trip") } catch (_: IllegalArgumentException) { }
        repo.deleteInstallation(next)
        assertEquals(original.periodId, repo.observeCounts().first().single().periodId)
        try { repo.deleteInstallation(original.periodId); fail("only period") } catch (_: IllegalArgumentException) { }
    }

    @Test fun masterEditsKeepIdentityAndInstallationHistory() = runTest {
        val p = panel(); val b = repo.saveBreaker(breaker(p))
        val before = repo.observeCounts().first().single()
        repo.registerTrip(TripRegistration(before.breaker, today, "당시 장소", periodId = before.periodId,
            requestId = "one", reason = "누전", note = "보존"))
        repo.savePanel(repo.observePanels().first().single().copy(number = "LP-NEW", location = "새 복도"))
        repo.saveBreaker(repo.observeBreakers().first().single().copy(number = "R2", load = "새 부하"))
        val after = repo.observeTrips().first().single()
        assertEquals(before.periodId, after.periodId)
        assertEquals("LP-NEW", after.breaker.panelNumber)
        assertEquals("R2", after.breaker.breakerName)
        assertEquals("당시 장소", after.location)
        assertEquals("보존", after.note)
        assertEquals(b, repo.observeBreakers().first().single().id)
    }

    @Test fun invalidMetadataAndDuplicateIdentityLeaveNoPartialRows() = runTest {
        val p = panel()
        for (invalid in listOf(breaker(p).copy(ports = 0), breaker(p).copy(ratedAmps = "NaN"),
            breaker(p).copy(ratedAmps = "-1"), breaker(p).copy(kind = " "), breaker(p).copy(load = " "),
            breaker(p).copy(installationDate = today.plusDays(1)))) {
            try { repo.saveBreaker(invalid); fail("invalid metadata") } catch (_: IllegalArgumentException) { }
        }
        assertTrue(repo.observeBreakers().first().isEmpty())
        repo.saveBreaker(breaker(p))
        try { repo.saveBreaker(breaker(p)); fail("duplicate") } catch (_: IllegalArgumentException) { }
        assertEquals(1, repo.observeBreakers().first().size)
    }

    @Test fun masterTripRequiresRegisteredBreakerPeriodAndSeparateReason() = runTest {
        repo.saveBreaker(breaker(panel()))
        val row = repo.observeCounts().first().single()
        val request = TripRegistration(row.breaker, today, "현장", periodId = row.periodId, requestId = "strict", note = "특기사항")
        try { repo.saveMasterTrip(null, request); fail("reason required") } catch (_: IllegalArgumentException) { }
        try { repo.saveMasterTrip(null, request.copy(breaker = row.breaker.copy(breakerName = "MISSING"), reason = "과부하")); fail("existing master required") } catch (_: IllegalArgumentException) { }
        repo.saveMasterTrip(null, request.copy(reason = "과부하"))
        val saved = repo.observeTrips().first().single()
        assertEquals("특기사항", saved.note)
        assertEquals("과부하", saved.reason)
        assertEquals(1, repo.observeBreakers().first().size)
    }

    @Test fun unknownInstallationStillRespectsKnownEarlierBoundary() = runTest {
        val b = repo.saveBreaker(breaker(panel()))
        val next = repo.replaceBreaker(b, today.minusDays(5))
        repo.correctInstallation(next, null)
        val current = repo.observeCounts().first().single()
        val request = TripRegistration(current.breaker, today.minusDays(11), "현장", periodId = next, requestId = "boundary", reason = "과부하")
        try { repo.saveMasterTrip(null, request); fail("known earlier boundary must apply") } catch (_: IllegalArgumentException) { }
        assertTrue(repo.observeTrips().first().isEmpty())
        repo.saveMasterTrip(null, request.copy(tripDate = today.minusDays(10)))
        assertEquals(1L, repo.observeCounts().first().single().tripCount)
    }
}
