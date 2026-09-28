package com.triptracker.app.domain

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

data class PanelMaster(
    val id: Long = 0, val building: Int, val floor: String, val number: String,
    val location: String = "", val note: String = "",
)
data class BreakerMaster(
    val id: Long = 0, val panelId: Long, val number: String, val kind: String = "",
    val ports: Int? = null, val ratedAmps: String = "", val load: String = "", val note: String = "",
    val installationDate: LocalDate? = null, val installationUnknown: Boolean = true,
    val currentPeriodId: Long? = null,
)
data class BreakerLocation(val panel: PanelMaster, val breaker: BreakerMaster)

interface MasterRepository {
    fun observePanels(): Flow<List<PanelMaster>>
    fun observeBreakers(): Flow<List<BreakerMaster>>
    fun observeLocations(query: String): Flow<List<BreakerLocation>>
    suspend fun savePanel(panel: PanelMaster): Long
    suspend fun saveBreaker(breaker: BreakerMaster): Long
    suspend fun deletePanel(id: Long)
    suspend fun deleteBreaker(id: Long)
    suspend fun replaceBreaker(id: Long, date: LocalDate): Long
    suspend fun correctInstallation(periodId: Long, date: LocalDate?)
    suspend fun deleteInstallation(periodId: Long)
    suspend fun saveMasterTrip(id: Long?, registration: TripRegistration): Long
}
