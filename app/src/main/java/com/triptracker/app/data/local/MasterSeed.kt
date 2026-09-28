package com.triptracker.app.data.local

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.triptracker.app.domain.InputRules
import java.time.LocalDate
import org.json.JSONObject

/** Bundled masters are imported atomically once; user changes always take precedence. */
object MasterSeed {
    private const val DATASET = "excel-masters-v1"
    private const val SPARE_CLEANUP = "excel-masters-remove-spares-v1"

    fun callback(source: () -> String) = object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) = importOnce(db, source)
    }

    fun apply(db: SupportSQLiteDatabase, json: String) = importOnce(db) { json }

    private fun importOnce(db: SupportSQLiteDatabase, source: () -> String) {
        db.beginTransaction()
        try {
            val seeded = db.id("SELECT 1 FROM initial_data_import WHERE source_key = ?", DATASET) != null
            val cleaned = db.id("SELECT 1 FROM initial_data_import WHERE source_key = ?", SPARE_CLEANUP) != null
            if (seeded && cleaned) {
                db.setTransactionSuccessful()
                return
            }
            val data = JSONObject(source())
            require(data.getInt("formatVersion") == 1 && data.getString("datasetId") == DATASET)
            if (!seeded) {
                val panels = data.getJSONArray("panels")
                val parents = mutableMapOf<String, Parent>()
                for (i in 0 until panels.length()) {
                    val p = panels.getJSONObject(i)
                    val building = p.getInt("building")
                    val floor = p.getString("floor")
                    val number = p.getString("number")
                    require(building in InputRules.buildings && floor in InputRules.floorsFor(building))
                    require(number.isNotBlank() && number !in parents)
                    val id = db.id("SELECT id FROM panel WHERE building = ? AND floor = ? AND number = ?", building, floor, number)
                        ?: db.insertRow("INSERT INTO panel (building,floor,number,location,note) VALUES (?,?,?,?,?)",
                            building, floor, number, p.getString("location"), p.getString("note"))
                    parents[number] = Parent(id, building, floor)
                }
                val breakers = data.getJSONArray("breakers")
                for (i in 0 until breakers.length()) {
                    val b = breakers.getJSONObject(i)
                    val panel = b.getString("panelNumber")
                    val parent = requireNotNull(parents[panel]) { "Unknown parent panel: $panel" }
                    val number = b.getString("number")
                    require(number.isNotBlank())
                    if (db.id("SELECT id FROM breaker WHERE building = ? AND floor = ? AND panel_number = ? AND breaker_name = ?",
                            parent.building, parent.floor, panel, number) != null) continue
                    val ports = if (b.isNull("ports")) null else b.getInt("ports")
                    require(ports == null || ports > 0)
                    val amps = b.getString("ratedAmps")
                    require(amps.isEmpty() || amps.toBigDecimal().signum() > 0)
                    val day = if (b.isNull("installationDate")) null else LocalDate.parse(b.getString("installationDate")).toEpochDay()
                    val id = db.insertRow("""INSERT INTO breaker
                        (building,floor,panel_number,breaker_name,panel_id,kind,ports,rated_amps,load,note)
                        VALUES (?,?,?,?,?,?,?,?,?,?)""", parent.building, parent.floor, panel, number, parent.id,
                        b.getString("kind"), ports, amps, b.getString("load"), b.getString("note"))
                    val period = db.insertRow("INSERT INTO replacement_period (breaker_id,sequence,replacement_day,creation_token) VALUES (?,1,?,?)",
                        id, day, "$DATASET:breaker-row-${b.getInt("sourceRow")}")
                    db.execSQL("UPDATE breaker SET current_period_id = ? WHERE id = ?", arrayOf(period, id))
                }
                db.execSQL("INSERT INTO initial_data_import (source_key) VALUES (?)", arrayOf(DATASET))
            }
            if (!cleaned) {
                removeUnchangedSpares(db, data)
                db.execSQL("INSERT INTO initial_data_import (source_key) VALUES (?)", arrayOf(SPARE_CLEANUP))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun removeUnchangedSpares(db: SupportSQLiteDatabase, data: JSONObject) {
        val panels = data.getJSONArray("panels")
        val parents = (0 until panels.length()).map { panels.getJSONObject(it) }.associateBy { it.getString("number") }
        val spares = data.getJSONArray("excludedSpareBreakers")
        for (i in 0 until spares.length()) {
            val b = spares.getJSONObject(i)
            require(b.getString("originalNumber").trim().uppercase() in setOf("SPARE", "SPARE(증설)"))
            val parent = requireNotNull(parents[b.getString("panelNumber")])
            val day = if (b.isNull("installationDate")) null else LocalDate.parse(b.getString("installationDate")).toEpochDay()
            val ports = if (b.isNull("ports")) null else b.getInt("ports")
            val candidate = db.query("""
                SELECT b.id, p.id FROM breaker b JOIN replacement_period p ON p.breaker_id=b.id
                WHERE p.creation_token=? AND p.sequence=1 AND p.replacement_day IS ? AND b.current_period_id=p.id
                  AND b.building=? AND b.floor=? AND b.panel_number=? AND b.breaker_name=?
                  AND b.kind=? AND b.ports IS ? AND b.rated_amps=? AND b.load=? AND b.note=?
                  AND (SELECT COUNT(*) FROM replacement_period WHERE breaker_id=b.id)=1
                  AND NOT EXISTS (SELECT 1 FROM trip_event WHERE period_id=p.id)
            """.trimIndent(), arrayOf<Any?>("$DATASET:breaker-row-${b.getInt("sourceRow")}", day,
                parent.getInt("building"), parent.getString("floor"), b.getString("panelNumber"), b.getString("number"),
                b.getString("kind"), ports, b.getString("ratedAmps"), b.getString("load"), b.getString("note"))).use {
                if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null
            } ?: continue
            db.execSQL("UPDATE breaker SET current_period_id=NULL WHERE id=?", arrayOf(candidate.first))
            db.execSQL("DELETE FROM replacement_period WHERE id=?", arrayOf(candidate.second))
            db.execSQL("DELETE FROM breaker WHERE id=?", arrayOf(candidate.first))
        }
    }

    private data class Parent(val id: Long, val building: Int, val floor: String)

    private fun SupportSQLiteDatabase.id(sql: String, vararg args: Any?): Long? =
        query(sql, args).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun SupportSQLiteDatabase.insertRow(sql: String, vararg args: Any?): Long {
        execSQL(sql, args)
        return requireNotNull(id("SELECT last_insert_rowid()"))
    }
}
