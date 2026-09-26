package com.triptracker.app.domain

import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

data class BreakerKey(val building: Int, val floor: String, val panelNumber: String, val breakerName: String)
data class ReplacementPeriod(val id: Long, val replacementDate: LocalDate?, val sequence: Long, val isCurrent: Boolean)
data class NewTrip(val periodId: Long, val tripDate: LocalDate, val location: String, val note: String = "")
data class TripRegistration(
    val breaker: BreakerKey, val tripDate: LocalDate, val location: String, val note: String = "",
    val periodId: Long? = null, val replacementDate: LocalDate? = null,
    val replacementUnknown: Boolean = false, val requestId: String,
)
data class TripSearch(
    val building: Int? = null, val floor: String? = null,
    val panelNumber: String = "", val breakerName: String = "", val tripDate: LocalDate? = null,
)
enum class PeriodScope { CURRENT, PREVIOUS, ALL }
data class TripDetail(
    val id: Long, val periodId: Long, val breaker: BreakerKey,
    val tripDate: LocalDate, val replacementDate: LocalDate?,
    val location: String, val note: String, val createdAt: Instant,
)
data class TripCount(
    val periodId: Long, val breaker: BreakerKey, val replacementDate: LocalDate?,
    val isCurrent: Boolean, val location: String?, val tripCount: Long,
)

interface TripRepository {
    suspend fun updateTrip(id: Long, registration: TripRegistration)
    suspend fun deleteTrips(ids: Set<Long>): Int
    suspend fun registerTrip(registration: TripRegistration): Long
    suspend fun addTrip(trip: NewTrip): Long
    suspend fun findPeriods(breaker: BreakerKey): List<ReplacementPeriod>
    fun observeTrips(search: TripSearch = TripSearch()): Flow<List<TripDetail>>
    fun observeCounts(search: TripSearch = TripSearch(), scope: PeriodScope = PeriodScope.CURRENT): Flow<List<TripCount>>
}
