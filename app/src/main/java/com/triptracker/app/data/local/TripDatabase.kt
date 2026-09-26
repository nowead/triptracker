package com.triptracker.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [BreakerEntity::class, PeriodEntity::class, TripEntity::class], version = 1, exportSchema = true)
abstract class TripDatabase : RoomDatabase() {
    abstract fun dao(): TripDao
}
