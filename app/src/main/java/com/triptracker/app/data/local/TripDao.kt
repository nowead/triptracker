package com.triptracker.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class DetailRow(
    val id: Long, val periodId: Long, val building: Int, val floor: String,
    val panelNumber: String, val breakerName: String, val tripDay: Long,
    val replacementDay: Long?, val location: String, val note: String, val createdAt: Long,
)
data class CountRow(
    val periodId: Long, val building: Int, val floor: String, val panelNumber: String, val breakerName: String,
    val replacementDay: Long?, val isCurrent: Boolean, val location: String?, val tripCount: Long,
)

@Dao
interface TripDao {
    @Insert suspend fun insertBreaker(breaker: BreakerEntity): Long
    @Insert suspend fun insertPeriod(period: PeriodEntity): Long
    @Insert suspend fun insertTrip(trip: TripEntity): Long
    @Query("SELECT * FROM trip_event WHERE id = :id")
    suspend fun getTrip(id: Long): TripEntity?
    @Query("UPDATE trip_event SET period_id = :periodId, trip_day = :day, location = :location, note = :note WHERE id = :id")
    suspend fun updateTrip(id: Long, periodId: Long, day: Long, location: String, note: String): Int
    @Query("DELETE FROM trip_event WHERE id = :id")
    suspend fun deleteTrip(id: Long): Int
    @Query("SELECT * FROM trip_event WHERE creation_token = :token")
    suspend fun tripByToken(token: String): TripEntity?
    @Query("SELECT * FROM breaker WHERE id = :id")
    suspend fun getBreaker(id: Long): BreakerEntity?
    @Query("UPDATE breaker SET current_period_id = :period WHERE id = :breaker AND EXISTS (SELECT 1 FROM replacement_period WHERE id = :period AND breaker_id = :breaker)")
    suspend fun setCurrentPeriod(breaker: Long, period: Long): Int
    @Query("SELECT * FROM replacement_period WHERE id = :id")
    suspend fun getPeriod(id: Long): PeriodEntity?
    @Query("SELECT * FROM replacement_period WHERE breaker_id = :breaker AND sequence > :sequence ORDER BY sequence LIMIT 1")
    suspend fun nextPeriod(breaker: Long, sequence: Long): PeriodEntity?
    @Query("SELECT * FROM breaker WHERE building = :building AND floor = :floor AND panel_number = :panel AND breaker_name = :name")
    suspend fun findBreaker(building: Int, floor: String, panel: String, name: String): BreakerEntity?
    @Query("SELECT * FROM replacement_period WHERE breaker_id = :breaker ORDER BY sequence DESC")
    suspend fun periods(breaker: Long): List<PeriodEntity>

    @Query("""
        SELECT t.id, p.id AS periodId, b.building, b.floor, b.panel_number AS panelNumber,
            b.breaker_name AS breakerName, t.trip_day AS tripDay, p.replacement_day AS replacementDay,
            t.location, t.note, t.created_at AS createdAt
        FROM trip_event t JOIN replacement_period p ON p.id = t.period_id JOIN breaker b ON b.id = p.breaker_id
        WHERE (:building IS NULL OR b.building = :building) AND (:floor IS NULL OR b.floor = :floor)
            AND instr(b.panel_number, :panel) > 0 AND instr(b.breaker_name, :name) > 0
            AND (:day IS NULL OR t.trip_day = :day)
        ORDER BY t.trip_day DESC, t.created_at DESC, t.id DESC
    """)
    fun observeTrips(building: Int?, floor: String?, panel: String, name: String, day: Long?): Flow<List<DetailRow>>

    @Query("""
        SELECT p.id AS periodId, b.building, b.floor, b.panel_number AS panelNumber,
            b.breaker_name AS breakerName, p.replacement_day AS replacementDay,
            (b.current_period_id = p.id) AS isCurrent, COUNT(t.id) AS tripCount,
            (SELECT latest.location FROM trip_event latest WHERE latest.period_id = p.id
                ORDER BY latest.trip_day DESC, latest.created_at DESC, latest.id DESC LIMIT 1) AS location
        FROM replacement_period p JOIN breaker b ON b.id = p.breaker_id LEFT JOIN trip_event t ON t.period_id = p.id
        WHERE (:building IS NULL OR b.building = :building) AND (:floor IS NULL OR b.floor = :floor)
            AND instr(b.panel_number, :panel) > 0 AND instr(b.breaker_name, :name) > 0
            AND (:scope = 'ALL' OR (:scope = 'CURRENT' AND b.current_period_id = p.id)
                OR (:scope = 'PREVIOUS' AND b.current_period_id != p.id))
        GROUP BY p.id
        ORDER BY tripCount DESC, b.building,
            CASE WHEN b.floor = 'PH' THEN 12 WHEN substr(b.floor, 1, 1) = 'B'
                THEN -CAST(substr(b.floor, 2) AS INTEGER) ELSE CAST(b.floor AS INTEGER) END,
            b.panel_number, b.breaker_name, p.sequence DESC, p.id DESC
    """)
    fun observeCounts(building: Int?, floor: String?, panel: String, name: String, scope: String): Flow<List<CountRow>>
}
