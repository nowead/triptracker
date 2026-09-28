package com.triptracker.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PanelEntity::class, BreakerEntity::class, PeriodEntity::class, TripEntity::class, InitialDataImportEntity::class], version = 3, exportSchema = true)
abstract class TripDatabase : RoomDatabase() {
    abstract fun dao(): TripDao
    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS initial_data_import (source_key TEXT NOT NULL, PRIMARY KEY(source_key))")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS panel (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, building INTEGER NOT NULL, floor TEXT NOT NULL, number TEXT NOT NULL, location TEXT NOT NULL, note TEXT NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_panel_building_floor_number ON panel (building, floor, number)")
                db.execSQL("INSERT INTO panel (building, floor, number, location, note) SELECT DISTINCT building, floor, panel_number, '', '' FROM breaker ORDER BY building, floor, panel_number")
                db.execSQL("ALTER TABLE breaker ADD COLUMN panel_id INTEGER REFERENCES panel(id) ON DELETE RESTRICT")
                db.execSQL("ALTER TABLE breaker ADD COLUMN kind TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE breaker ADD COLUMN ports INTEGER")
                db.execSQL("ALTER TABLE breaker ADD COLUMN rated_amps TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE breaker ADD COLUMN load TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE breaker ADD COLUMN note TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_breaker_panel_id ON breaker (panel_id)")
                db.execSQL("UPDATE breaker SET panel_id = (SELECT id FROM panel WHERE panel.building = breaker.building AND panel.floor = breaker.floor AND panel.number = breaker.panel_number)")
                db.execSQL("ALTER TABLE trip_event ADD COLUMN reason TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
