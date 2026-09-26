package com.triptracker.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "breaker",
    indices = [Index(value = ["building", "floor", "panel_number", "breaker_name"], unique = true), Index("current_period_id")],
    foreignKeys = [ForeignKey(entity = PeriodEntity::class, parentColumns = ["id"], childColumns = ["current_period_id"], onDelete = ForeignKey.RESTRICT)],
)
data class BreakerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val building: Int, val floor: String,
    @ColumnInfo(name = "panel_number") val panelNumber: String,
    @ColumnInfo(name = "breaker_name") val breakerName: String,
    @ColumnInfo(name = "current_period_id") val currentPeriodId: Long? = null,
)

@Entity(
    tableName = "replacement_period",
    indices = [Index(value = ["breaker_id", "sequence"], unique = true), Index(value = ["creation_token"], unique = true)],
    foreignKeys = [ForeignKey(entity = BreakerEntity::class, parentColumns = ["id"], childColumns = ["breaker_id"], onDelete = ForeignKey.RESTRICT)],
)
data class PeriodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "breaker_id") val breakerId: Long,
    val sequence: Long,
    @ColumnInfo(name = "replacement_day") val replacementDay: Long?,
    @ColumnInfo(name = "creation_token") val creationToken: String = UUID.randomUUID().toString(),
)

@Entity(
    tableName = "trip_event",
    indices = [Index(value = ["period_id", "trip_day", "created_at", "id"]), Index(value = ["trip_day", "created_at", "id"]), Index(value = ["creation_token"], unique = true)],
    foreignKeys = [ForeignKey(entity = PeriodEntity::class, parentColumns = ["id"], childColumns = ["period_id"], onDelete = ForeignKey.RESTRICT)],
)
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "period_id") val periodId: Long,
    @ColumnInfo(name = "trip_day") val tripDay: Long,
    val location: String, val note: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "creation_token") val creationToken: String = UUID.randomUUID().toString(),
)
