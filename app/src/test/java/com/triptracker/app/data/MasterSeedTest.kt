package com.triptracker.app.data

import androidx.room.Room
import com.triptracker.app.data.local.*
import java.time.LocalDate
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
class MasterSeedTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun source() = context.assets.open("initial_masters.json").bufferedReader().use { it.readText() }
    private inline fun <T> TripDatabase.use(block: (TripDatabase) -> T): T = try { block(this) } finally { close() }
    private fun database() = Room.inMemoryDatabaseBuilder(context, TripDatabase::class.java).build()

    @Test fun bundledMastersHaveCorrectedValuesWithoutSparesAndNoTrips() = runTest {
        val json = JSONObject(source())
        assertEquals(setOf("분전함마스터", "차단기마스터"), json.getJSONObject("sourceSheets").keys().asSequence().toSet())
        assertFalse(json.has("historyDates"))
        database().use { db ->
            MasterSeed.apply(db.openHelper.writableDatabase, source())
            val panels = db.dao().observePanels().first()
            val breakers = db.dao().observeBreakers().first()
            assertEquals(164, panels.size)
            assertEquals(171, breakers.size)
            assertEquals(2, panels.single { it.number == "LP-ELEC-A" }.building)
            assertEquals(3, panels.single { it.number == "LP-ELEC-B" }.building)
            assertEquals("4관 EPS", panels.single { it.number == "D-LE-B2" }.location)
            assertTrue(panels.single { it.number == "LP-ELEC-A" }.note.contains("FROM분전함: LV-1B"))
            val corrected = breakers.filter { it.panelNumber == "A-LE-B2" && it.breakerName in (1..10).map { i -> "E$i" } }
            assertEquals(10, corrected.size)
            assertTrue(corrected.all { it.ports == 2 })
            val spares = breakers.filter { it.panelNumber == "C-LP-06" && it.breakerName.startsWith("SPARE [") }
            assertTrue(spares.isEmpty())
            assertTrue(breakers.none { it.breakerName.startsWith("SPARE") })
            assertEquals(118, breakers.count { it.load.isEmpty() })
            val periods = db.dao().observePeriods().first()
            assertEquals(171, periods.size)
            assertEquals(12, periods.count { it.replacementDay == null })
            val main = breakers.single { it.panelNumber == "C-LP-06" && it.breakerName == "MAIN" }
            assertEquals(LocalDate.of(2011, 10, 1).toEpochDay(), periods.single { it.id == main.currentPeriodId }.replacementDay)
            assertTrue(db.dao().observeTrips(null, null, "", "", null).first().isEmpty())
            assertTrue(db.dao().observeCounts(null, null, "", "", "ALL").first().all { it.tripCount == 0L })
            db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }
    }

    @Test fun reopeningDoesNotRestoreDeletedMastersOrOverwriteEdits() = runTest {
        val name = "master-seed-reopen.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, TripDatabase::class.java, name)
            .addCallback(MasterSeed.callback { source() }).build()
        var db = open()
        try {
            val panels = db.dao().observePanels().first()
            val edited = panels.first().copy(location = "수정한 위치")
            db.dao().updatePanel(edited)
            val breaker = db.dao().observeBreakers().first().first()
            db.dao().clearCurrent(breaker.id)
            db.dao().deleteBreakerPeriods(breaker.id)
            db.dao().deleteBreaker(breaker.id)
            db.close()
            db = open()
            assertEquals(170, db.dao().observeBreakers().first().size)
            assertEquals("수정한 위치", db.dao().getPanel(edited.id)!!.location)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun existingMasterIdsPeriodsAndTripsRemainUntouched() = runTest {
        database().use { db ->
            val dao = db.dao()
            val p = dao.insertPanel(PanelEntity(building = 3, floor = "6", number = "C-LP-06", location = "기존 위치"))
            val b = dao.insertBreaker(BreakerEntity(building = 3, floor = "6", panelNumber = "C-LP-06", breakerName = "MAIN", panelId = p, note = "사용자 메모"))
            val period = dao.insertPeriod(PeriodEntity(breakerId = b, sequence = 1, replacementDay = null))
            dao.setCurrentPeriod(b, period)
            val trip = TripEntity(periodId = period, tripDay = 20000, location = "기존 장소", note = "보존", createdAt = 1234)
            val t = dao.insertTrip(trip)
            MasterSeed.apply(db.openHelper.writableDatabase, source())
            assertEquals(164, dao.observePanels().first().size)
            assertEquals(171, dao.observeBreakers().first().size)
            assertEquals("기존 위치", dao.getPanel(p)!!.location)
            assertEquals("사용자 메모", dao.getBreaker(b)!!.note)
            assertEquals(period, dao.getBreaker(b)!!.currentPeriodId)
            assertEquals(trip.copy(id = t), dao.getTrip(t))
        }
    }

    @Test fun versionTwoUpgradeKeepsExistingDataAndSeedsOnlyOnce() = runTest {
        val name = "master-seed-v2.db"
        context.deleteDatabase(name)
        val schema = JSONObject(java.io.File("schemas/com.triptracker.app.data.local.TripDatabase/2.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, 0, null).use { sql ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                sql.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            sql.execSQL("INSERT INTO panel VALUES (900,1,'13','MY-PANEL','내 위치','내 메모')")
            sql.execSQL("INSERT INTO breaker (id,building,floor,panel_number,breaker_name,panel_id) VALUES (901,1,'13','MY-PANEL','R1',900)")
            sql.execSQL("INSERT INTO replacement_period VALUES (902,901,1,NULL,'existing-period')")
            sql.execSQL("UPDATE breaker SET current_period_id=902 WHERE id=901")
            sql.execSQL("INSERT INTO trip_event VALUES (903,902,20000,'내 장소','내 비고',1234,'existing-trip','내 사유')")
            sql.version = 2
        }
        val db = Room.databaseBuilder(context, TripDatabase::class.java, name)
            .addMigrations(TripDatabase.MIGRATION_2_3).addCallback(MasterSeed.callback { source() }).build()
        try {
            assertEquals(165, db.dao().observePanels().first().size)
            assertEquals(172, db.dao().observeBreakers().first().size)
            assertEquals("내 사유", db.dao().getTrip(903)!!.reason)
            assertEquals(902L, db.dao().getBreaker(901)!!.currentPeriodId)
            MasterSeed.apply(db.openHelper.writableDatabase, source())
            assertEquals(172, db.dao().observeBreakers().first().size)
            db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun removesOnlyUnchangedSeededSparesAndNeverRepeatsCleanup() = runTest {
        database().use { db ->
            val legacy = JSONObject(source())
            val removed = legacy.getJSONArray("excludedSpareBreakers")
            for (i in 0 until removed.length()) legacy.getJSONArray("breakers").put(removed.getJSONObject(i))
            legacy.put("excludedSpareBreakers", org.json.JSONArray())
            MasterSeed.apply(db.openHelper.writableDatabase, legacy.toString())
            db.openHelper.writableDatabase.execSQL("DELETE FROM initial_data_import WHERE source_key='excel-masters-remove-spares-v1'")
            val dao = db.dao()
            val spares = dao.observeBreakers().first().filter { it.breakerName.startsWith("SPARE") }
            assertEquals(87, spares.size)
            val edited = spares[0].copy(breakerName = "사용중 R1", note = "사용자 수정")
            dao.updateBreaker(edited)
            val withTrip = spares[1]
            val trip = dao.insertTrip(TripEntity(periodId = withTrip.currentPeriodId!!, tripDay = 20000,
                location = "현장", note = "보존", createdAt = 1234))
            val manual = dao.insertBreaker(spares[2].copy(id = 0, breakerName = "SPARE [USER]", currentPeriodId = null))
            MasterSeed.apply(db.openHelper.writableDatabase, source())
            assertEquals(174, dao.observeBreakers().first().size)
            assertEquals(edited, dao.getBreaker(edited.id))
            assertNotNull(dao.getTrip(trip))
            assertNotNull(dao.getBreaker(manual))
            assertNull(dao.getBreaker(spares[2].id))
            dao.updateBreaker(spares[0])
            MasterSeed.apply(db.openHelper.writableDatabase, source())
            assertNotNull(dao.getBreaker(spares[0].id))
            db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }
    }

    @Test fun failedImportRollsBackAndCanBeRetried() = runTest {
        database().use { db ->
            val broken = JSONObject(source())
            val breakers = broken.getJSONArray("breakers")
            breakers.getJSONObject(breakers.length() - 1).put("panelNumber", "MISSING-PANEL")
            try { MasterSeed.apply(db.openHelper.writableDatabase, broken.toString()); fail("missing parent") }
            catch (_: IllegalArgumentException) { }
            assertTrue(db.dao().observePanels().first().isEmpty())
            assertTrue(db.dao().observeBreakers().first().isEmpty())
            MasterSeed.apply(db.openHelper.writableDatabase, source())
            assertEquals(171, db.dao().observeBreakers().first().size)
        }
    }
}
