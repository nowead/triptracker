package com.triptracker.app.data.repository

import com.triptracker.app.data.local.TripDatabase
import com.triptracker.app.data.local.TripEntity
import com.triptracker.app.data.local.BreakerEntity
import com.triptracker.app.data.local.PeriodEntity
import com.triptracker.app.domain.*
import androidx.room.withTransaction
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

class RoomTripRepository(private val db: TripDatabase, private val clock: Clock) : TripRepository {
    private val dao = db.dao()

    override suspend fun registerTrip(registration: TripRegistration): Long = writeTrip(registration)

    override suspend fun updateTrip(id: Long, registration: TripRegistration) {
        writeTrip(registration, id)
    }

    override suspend fun deleteTrips(ids: Set<Long>): Int = db.withTransaction {
        var deleted = 0
        for (id in ids.sorted()) deleted += dao.deleteTrip(id)
        deleted
    }

    private suspend fun writeTrip(registration: TripRegistration, editId: Long? = null): Long = db.withTransaction {
        if (editId != null) requireNotNull(dao.getTrip(editId)) { "수정할 이력이 없습니다. 목록을 다시 조회하세요." }
        val key = registration.breaker.copy(
            panelNumber = InputRules.normalizeRequiredText(registration.breaker.panelNumber),
            breakerName = InputRules.normalizeRequiredText(registration.breaker.breakerName),
        )
        require(key.building in InputRules.buildings && key.floor in InputRules.floors) { "관과 층을 선택하세요." }
        require(key.panelNumber.isNotEmpty() && key.breakerName.isNotEmpty()) { "분전함번호와 차단기명을 입력하세요." }
        val location = InputRules.normalizeRequiredText(registration.location)
        require(location.isNotEmpty()) { "장소를 입력하세요." }
        require(registration.requestId.isNotBlank()) { "저장 요청을 확인하세요." }
        val existing = dao.findBreaker(key.building, key.floor, key.panelNumber, key.breakerName)
        if (editId == null) dao.tripByToken(registration.requestId)?.let { saved ->
            val savedPeriod = requireNotNull(dao.getPeriod(saved.periodId))
            require(existing?.id == savedPeriod.breakerId && saved.tripDay == registration.tripDate.toEpochDay() &&
                saved.location == location && saved.note == registration.note &&
                (registration.periodId == saved.periodId || (registration.periodId == null &&
                    savedPeriod.replacementDay == registration.replacementDate?.toEpochDay() &&
                    (registration.replacementDate != null || registration.replacementUnknown)))) {
                "이미 저장된 요청과 내용이 다릅니다. 새 등록으로 입력하세요."
            }
            return@withTransaction saved.id
        }
        val period = if (existing == null) {
            require(registration.periodId == null) { "차단기와 교체 구간을 다시 확인하세요." }
            require((registration.replacementDate != null) xor registration.replacementUnknown) { "마지막 교체일자 또는 교체일 미상을 선택하세요." }
            require(registration.replacementDate?.isAfter(InputRules.today(clock)) != true) { "교체일자는 미래일 수 없습니다." }
            val breakerId = dao.insertBreaker(BreakerEntity(building = key.building, floor = key.floor,
                panelNumber = key.panelNumber, breakerName = key.breakerName))
            val periodId = dao.insertPeriod(PeriodEntity(breakerId = breakerId, sequence = 1,
                replacementDay = registration.replacementDate?.toEpochDay()))
            check(dao.setCurrentPeriod(breakerId, periodId) == 1)
            requireNotNull(dao.getPeriod(periodId))
        } else {
            val selected = requireNotNull(registration.periodId) { "이미 등록된 차단기입니다. 교체 구간을 다시 확인하세요." }
            requireNotNull(dao.getPeriod(selected)).also {
                require(it.breakerId == existing.id) { "선택한 구간이 이 차단기에 속하지 않습니다." }
            }
        }
        val next = dao.nextPeriod(period.breakerId, period.sequence)
        require(InputRules.isTripDateValid(registration.tripDate, period.replacementDay.asDate(), next?.replacementDay.asDate(), clock)) {
            "트립일자가 선택한 구간의 날짜 범위를 벗어나거나 미래입니다."
        }
        if (editId == null) {
            dao.insertTrip(TripEntity(periodId = period.id, tripDay = registration.tripDate.toEpochDay(),
                location = location, note = registration.note, createdAt = clock.millis(), creationToken = registration.requestId))
        } else {
            check(dao.updateTrip(editId, period.id, registration.tripDate.toEpochDay(), location, registration.note) == 1)
            editId
        }
    }

    override suspend fun addTrip(trip: NewTrip): Long = db.withTransaction {
        val period = requireNotNull(dao.getPeriod(trip.periodId)) { "교체 구간을 다시 선택하세요." }
        val next = dao.nextPeriod(period.breakerId, period.sequence)
        val location = InputRules.normalizeRequiredText(trip.location)
        require(location.isNotEmpty()) { "장소를 입력하세요." }
        require(InputRules.isTripDateValid(trip.tripDate, period.replacementDay.asDate(), next?.replacementDay.asDate(), clock)) {
            "트립일자는 오늘 또는 과거 날짜이며 선택한 교체 구간의 날짜 범위 안에 있어야 합니다."
        }
        dao.insertTrip(TripEntity(periodId = period.id, tripDay = trip.tripDate.toEpochDay(),
            location = location, note = trip.note, createdAt = clock.millis()))
    }

    override suspend fun findPeriods(breaker: BreakerKey): List<ReplacementPeriod> = db.withTransaction {
        val found = dao.findBreaker(breaker.building, breaker.floor,
            InputRules.normalizeRequiredText(breaker.panelNumber), InputRules.normalizeRequiredText(breaker.breakerName))
            ?: return@withTransaction emptyList()
        dao.periods(found.id).map {
            ReplacementPeriod(it.id, it.replacementDay.asDate(), it.sequence, it.id == found.currentPeriodId)
        }.sortedByDescending { it.isCurrent }
    }

    override fun observeTrips(search: TripSearch): Flow<List<TripDetail>> = dao.observeTrips(
        search.building, search.floor, InputRules.normalizeRequiredText(search.panelNumber),
        InputRules.normalizeRequiredText(search.breakerName), search.tripDate?.toEpochDay(),
    ).map { rows -> rows.map {
        TripDetail(it.id, it.periodId, BreakerKey(it.building, it.floor, it.panelNumber, it.breakerName),
            LocalDate.ofEpochDay(it.tripDay), it.replacementDay.asDate(), it.location, it.note, Instant.ofEpochMilli(it.createdAt))
    } }.distinctUntilChanged()

    override fun observeCounts(search: TripSearch, scope: PeriodScope): Flow<List<TripCount>> = dao.observeCounts(
        search.building, search.floor, InputRules.normalizeRequiredText(search.panelNumber),
        InputRules.normalizeRequiredText(search.breakerName), scope.name,
    ).map { rows -> rows.map {
        TripCount(it.periodId, BreakerKey(it.building, it.floor, it.panelNumber, it.breakerName),
            it.replacementDay.asDate(), it.isCurrent, it.location, it.tripCount)
    } }.distinctUntilChanged()

    private fun Long?.asDate(): LocalDate? = this?.let(LocalDate::ofEpochDay)
}
