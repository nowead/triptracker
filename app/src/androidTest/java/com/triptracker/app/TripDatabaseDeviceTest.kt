package com.triptracker.app

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.triptracker.app.data.local.BreakerEntity
import com.triptracker.app.data.local.PeriodEntity
import com.triptracker.app.data.local.TripDatabase
import com.triptracker.app.data.repository.RoomTripRepository
import com.triptracker.app.domain.NewTrip
import com.triptracker.app.domain.TripRegistration
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TripDatabaseDeviceTest {
    @Test fun twoSameDayTripsPersistAfterReopeningDatabase() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Separate from the application's database; only this test file is removed.
        val name = "trip-device-test.db"
        val clock = Clock.fixed(Instant.parse("2026-09-15T01:00:00Z"), ZoneId.of("Asia/Seoul"))
        context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, TripDatabase::class.java, name).build()
        try {
            val periodId = db.withTransaction {
                val breaker = db.dao().insertBreaker(BreakerEntity(building = 1, floor = "3", panelNumber = "LP-3-A", breakerName = "R1"))
                val period = db.dao().insertPeriod(PeriodEntity(breakerId = breaker, sequence = 1, replacementDay = null))
                db.dao().setCurrentPeriod(breaker, period)
                period
            }
            var repository = RoomTripRepository(db, clock)
            val trip = NewTrip(periodId, LocalDate.of(2026, 9, 15), "사무실")
            val first = repository.addTrip(trip)
            val second = repository.addTrip(trip)
            assertNotEquals(first, second)
            db.close()
            db = Room.databaseBuilder(context, TripDatabase::class.java, name).build()
            repository = RoomTripRepository(db, clock)
            assertEquals(listOf(second, first), repository.observeTrips().first().map { it.id })
            assertEquals(2L, repository.observeCounts().first().single().tripCount)
            assertNull(repository.observeCounts().first().single().replacementDate)
            val original = repository.observeTrips().first().first { it.id == first }
            repository.updateTrip(first, TripRegistration(original.breaker, original.tripDate, "변경 장소",
                periodId = periodId, requestId = "device-edit"))
            assertEquals(original.createdAt, repository.observeTrips().first().first { it.id == first }.createdAt)
            assertEquals("사무실", repository.observeTrips().first().first { it.id == second }.location)
            assertEquals(2, repository.deleteTrips(setOf(first, second)))
            assertEquals(0L, repository.observeCounts().first().single().tripCount)
            db.close()
            db = Room.databaseBuilder(context, TripDatabase::class.java, name).build()
            repository = RoomTripRepository(db, clock)
            assertTrue(repository.observeTrips().first().isEmpty())
            assertEquals(0L, repository.observeCounts().first().single().tripCount)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
