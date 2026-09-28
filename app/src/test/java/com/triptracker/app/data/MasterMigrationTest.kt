package com.triptracker.app.data

import android.content.Context
import androidx.room.Room
import com.triptracker.app.data.local.TripDatabase
import com.triptracker.app.data.repository.RoomTripRepository
import java.io.File
import java.time.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MasterMigrationTest {
    @Test fun v1MigrationPreservesIdsPeriodsCountsAndNotesWithoutInventingMetadata() = runTest {
        val context: Context = RuntimeEnvironment.getApplication()
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/com.triptracker.app.data.local.TripDatabase/1.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, 0, null).use { sql ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                sql.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))
            sql.execSQL("INSERT INTO breaker VALUES (21,1,'3','LP','R1',NULL)")
            sql.execSQL("INSERT INTO replacement_period VALUES (31,21,1,NULL,'old-period')")
            sql.execSQL("UPDATE breaker SET current_period_id=31 WHERE id=21")
            sql.execSQL("INSERT INTO trip_event VALUES (41,31,20000,'당시 장소','원래 비고',1234,'old-trip')")
            sql.version = 1
        }
        val db = Room.databaseBuilder(context, TripDatabase::class.java, name).addMigrations(TripDatabase.MIGRATION_1_2, TripDatabase.MIGRATION_2_3).build()
        try {
            val repo = RoomTripRepository(db, Clock.systemUTC())
            val trip = repo.observeTrips().first().single()
            assertEquals(41L, trip.id); assertEquals(31L, trip.periodId)
            assertEquals("원래 비고", trip.note); assertEquals("", trip.reason)
            assertNull(trip.replacementDate)
            assertEquals(1L, repo.observeCounts().first().single().tripCount)
            assertEquals("", repo.observePanels().first().single().location)
            val breaker = repo.observeBreakers().first().single()
            assertEquals(21L, breaker.id); assertEquals("", breaker.load); assertNull(breaker.ports)
            assertEquals(repo.observePanels().first().single().id, breaker.panelId)
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
