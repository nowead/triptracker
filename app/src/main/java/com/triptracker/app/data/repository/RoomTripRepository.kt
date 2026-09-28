package com.triptracker.app.data.repository

import com.triptracker.app.data.local.TripDatabase
import com.triptracker.app.data.local.TripEntity
import com.triptracker.app.data.local.BreakerEntity
import com.triptracker.app.data.local.PanelEntity
import com.triptracker.app.data.local.PeriodEntity
import kotlinx.coroutines.flow.combine
import com.triptracker.app.domain.*
import androidx.room.withTransaction
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

class RoomTripRepository(private val db: TripDatabase, private val clock: Clock) : TripRepository, MasterRepository {
    private val dao = db.dao()

    override fun observePanels(): Flow<List<PanelMaster>> = dao.observePanels().map { rows -> rows.map { it.master() } }
    override fun observeBreakers(): Flow<List<BreakerMaster>> = combine(dao.observeBreakers(), dao.observePeriods()) { breakers, periods ->
        breakers.mapNotNull { b -> b.panelId?.let { panel ->
            val period = periods.firstOrNull { it.id == b.currentPeriodId }
            BreakerMaster(b.id, panel, b.breakerName, b.kind, b.ports, b.ratedAmps, b.load, b.note,
                period?.replacementDay.asDate(), period?.replacementDay == null, b.currentPeriodId)
        } }
    }.distinctUntilChanged()

    override fun observeLocations(query: String): Flow<List<BreakerLocation>> = combine(observePanels(), observeBreakers()) { panels, breakers ->
        val byId = panels.associateBy { it.id }
        val term = InputRules.normalizeRequiredText(query)
        breakers.filter { InputRules.normalizeRequiredText(it.load).contains(term) }.mapNotNull { b ->
            byId[b.panelId]?.let { BreakerLocation(it, b) }
        }
    }.distinctUntilChanged()

    override suspend fun savePanel(panel: PanelMaster): Long = db.withTransaction {
        val number = InputRules.normalizeRequiredText(panel.number)
        val location = InputRules.normalizeRequiredText(panel.location)
        require(panel.building in InputRules.buildings && panel.floor in InputRules.floorsFor(panel.building)) { "관과 층을 선택하세요." }
        require(number.isNotEmpty() && location.isNotEmpty()) { "분전함번호와 분전함위치를 입력하세요." }
        val duplicate = dao.findPanel(panel.building, panel.floor, number)
        require(duplicate == null || duplicate.id == panel.id) { "같은 관·층에 이미 등록된 분전함번호입니다." }
        val entity = PanelEntity(panel.id, panel.building, panel.floor, number, location, panel.note)
        if (panel.id == 0L) dao.insertPanel(entity) else {
            requireNotNull(dao.getPanel(panel.id)) { "분전함이 없습니다." }
            check(dao.updatePanel(entity) == 1)
            dao.syncPanelKey(panel.id, panel.building, panel.floor, number)
            panel.id
        }
    }

    override suspend fun saveBreaker(breaker: BreakerMaster): Long = db.withTransaction {
        val panel = requireNotNull(dao.getPanel(breaker.panelId)) { "소속 분전함을 선택하세요." }
        val number = InputRules.normalizeRequiredText(breaker.number)
        val kind = breaker.kind.trim()
        val load = InputRules.normalizeRequiredText(breaker.load)
        require(number.isNotEmpty() && kind.isNotEmpty() && load.isNotEmpty()) { "차단기번호·종류·장소(부하)를 입력하세요." }
        require((breaker.ports ?: 0) > 0) { "port 수는 양의 정수로 입력하세요." }
        val amps = breaker.ratedAmps.trim().toBigDecimalOrNull()
        require(amps != null && amps.signum() > 0) { "정격전류는 양의 A 값으로 입력하세요." }
        require((breaker.installationDate != null) xor breaker.installationUnknown) { "설치일자 또는 설치일 미상을 선택하세요." }
        require(breaker.installationDate?.isAfter(InputRules.today(clock)) != true) { "미래 설치일자는 저장할 수 없습니다." }
        val duplicate = dao.findBreaker(panel.building, panel.floor, panel.number, number)
        require(duplicate == null || duplicate.id == breaker.id) { "해당 분전함에 이미 등록된 차단기번호입니다." }
        val old = if (breaker.id != 0L) requireNotNull(dao.getBreaker(breaker.id)) { "차단기가 없습니다." } else null
        val entity = BreakerEntity(breaker.id, panel.building, panel.floor, panel.number, number, old?.currentPeriodId,
            panel.id, kind, breaker.ports, amps.stripTrailingZeros().toPlainString(), load, breaker.note)
        if (old == null) {
            val id = dao.insertBreaker(entity)
            val period = dao.insertPeriod(PeriodEntity(breakerId = id, sequence = 1, replacementDay = breaker.installationDate?.toEpochDay()))
            check(dao.setCurrentPeriod(id, period) == 1)
            id
        } else {
            val current = requireNotNull(old.currentPeriodId)
            correctInstallation(current, breaker.installationDate)
            check(dao.updateBreaker(entity) == 1)
            old.id
        }
    }

    override suspend fun deletePanel(id: Long) { db.withTransaction {
        require(dao.panelChildren(id) == 0) { "소속 차단기가 있어 삭제할 수 없습니다. 차단기를 먼저 확인하세요." }
        require(dao.deletePanel(id) == 1) { "분전함이 없습니다." }
    } }
    override suspend fun deleteBreaker(id: Long) { db.withTransaction {
        require(dao.breakerTrips(id) == 0) { "트립 이력이 있어 차단기를 삭제할 수 없습니다." }
        dao.clearCurrent(id)
        dao.deleteBreakerPeriods(id)
        require(dao.deleteBreaker(id) == 1) { "차단기가 없습니다." }
    } }

    override suspend fun replaceBreaker(id: Long, date: LocalDate): Long = db.withTransaction {
        requireNotNull(dao.getBreaker(id)) { "차단기가 없습니다." }
        require(!date.isAfter(InputRules.today(clock))) { "미래 설치일자는 저장할 수 없습니다." }
        val periods = dao.periods(id)
        val next = PeriodEntity(breakerId = id, sequence = (periods.maxOfOrNull { it.sequence } ?: 0) + 1,
            replacementDay = date.toEpochDay())
        validatePeriods(periods + next)
        val periodId = dao.insertPeriod(next)
        check(dao.setCurrentPeriod(id, periodId) == 1)
        periodId
    }
    override suspend fun correctInstallation(periodId: Long, date: LocalDate?) { db.withTransaction {
        val period = requireNotNull(dao.getPeriod(periodId)) { "설치 구간이 없습니다." }
        require(date?.isAfter(InputRules.today(clock)) != true) { "미래 설치일자는 저장할 수 없습니다." }
        validatePeriods(dao.periods(period.breakerId).map { if (it.id == periodId) it.copy(replacementDay = date?.toEpochDay()) else it })
        dao.correctInstallation(periodId, date?.toEpochDay())
    } }
    override suspend fun deleteInstallation(periodId: Long) { db.withTransaction {
        val period = requireNotNull(dao.getPeriod(periodId)) { "설치 구간이 없습니다." }
        val breaker = requireNotNull(dao.getBreaker(period.breakerId))
        val periods = dao.periods(breaker.id)
        require(periods.size > 1) { "유일한 설치 구간은 삭제할 수 없습니다." }
        require(dao.periodTrips(periodId).isEmpty()) { "트립이 있는 설치 구간은 삭제할 수 없습니다." }
        val remaining = periods.filter { it.id != periodId }
        validatePeriods(remaining)
        if (breaker.currentPeriodId == periodId) check(dao.setCurrentPeriod(breaker.id, remaining.maxBy { it.sequence }.id) == 1)
        dao.deleteInstallation(periodId)
    } }
    private suspend fun validatePeriods(periods: List<PeriodEntity>) {
        val ordered = periods.sortedBy { it.sequence }
        val known = ordered.mapNotNull { it.replacementDay }
        require(known.zipWithNext().all { (a, b) -> a <= b }) { "설치일자는 구간 순서대로 입력하세요." }
        for ((index, period) in ordered.withIndex()) {
            val start = ordered.take(index + 1).lastOrNull { it.replacementDay != null }?.replacementDay.asDate()
            val end = ordered.drop(index + 1).firstOrNull { it.replacementDay != null }?.replacementDay.asDate()
            for (trip in dao.periodTrips(period.id)) require(InputRules.isTripDateValid(LocalDate.ofEpochDay(trip.tripDay), start, end, clock)) {
                "설치일자가 기존 트립일자와 맞지 않습니다. 이력 또는 설치일자를 먼저 확인하세요."
            }
        }
    }
    private fun PanelEntity.master() = PanelMaster(id, building, floor, number, location, note)

    override suspend fun saveMasterTrip(id: Long?, registration: TripRegistration): Long = db.withTransaction {
        require(registration.reason.isNotBlank()) { "트립사유를 입력하세요." }
        val key = registration.breaker
        requireNotNull(dao.findBreaker(key.building, key.floor, InputRules.normalizeRequiredText(key.panelNumber),
            InputRules.normalizeRequiredText(key.breakerName))) { "차단기 마스터를 먼저 등록하세요." }
        requireNotNull(registration.periodId) { "설치 구간을 선택하세요." }
        writeTrip(registration, id)
    }

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
        require(key.building in InputRules.buildings && key.floor in InputRules.floorsFor(key.building)) { "관과 층을 선택하세요." }
        require(key.panelNumber.isNotEmpty() && key.breakerName.isNotEmpty()) { "분전함번호와 차단기명을 입력하세요." }
        val location = InputRules.normalizeRequiredText(registration.location)
        require(location.isNotEmpty()) { "장소를 입력하세요." }
        require(registration.requestId.isNotBlank()) { "저장 요청을 확인하세요." }
        val existing = dao.findBreaker(key.building, key.floor, key.panelNumber, key.breakerName)
        if (editId == null) dao.tripByToken(registration.requestId)?.let { saved ->
            val savedPeriod = requireNotNull(dao.getPeriod(saved.periodId))
            require(existing?.id == savedPeriod.breakerId && saved.tripDay == registration.tripDate.toEpochDay() &&
                saved.location == location && saved.note == registration.note && saved.reason == registration.reason.trim() &&
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
            val panelId = dao.findPanel(key.building, key.floor, key.panelNumber)?.id
                ?: dao.insertPanel(PanelEntity(building = key.building, floor = key.floor, number = key.panelNumber))
            val breakerId = dao.insertBreaker(BreakerEntity(building = key.building, floor = key.floor,
                panelNumber = key.panelNumber, breakerName = key.breakerName, panelId = panelId))
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
        validateTripDate(period, registration.tripDate)
        if (editId == null) {
            dao.insertTrip(TripEntity(periodId = period.id, tripDay = registration.tripDate.toEpochDay(),
                location = location, note = registration.note, createdAt = clock.millis(), creationToken = registration.requestId, reason = registration.reason.trim()))
        } else {
            check(dao.updateTrip(editId, period.id, registration.tripDate.toEpochDay(), location, registration.note, registration.reason.trim()) == 1)
            editId
        }
    }

    override suspend fun addTrip(trip: NewTrip): Long = db.withTransaction {
        val period = requireNotNull(dao.getPeriod(trip.periodId)) { "교체 구간을 다시 선택하세요." }
        val location = InputRules.normalizeRequiredText(trip.location)
        require(location.isNotEmpty()) { "장소를 입력하세요." }
        validateTripDate(period, trip.tripDate)
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
            LocalDate.ofEpochDay(it.tripDay), it.replacementDay.asDate(), it.location, it.note, Instant.ofEpochMilli(it.createdAt), it.reason)
    } }.distinctUntilChanged()

    override fun observeCounts(search: TripSearch, scope: PeriodScope): Flow<List<TripCount>> = dao.observeCounts(
        search.building, search.floor, InputRules.normalizeRequiredText(search.panelNumber),
        InputRules.normalizeRequiredText(search.breakerName), scope.name,
    ).map { rows -> rows.map {
        TripCount(it.periodId, BreakerKey(it.building, it.floor, it.panelNumber, it.breakerName),
            it.replacementDay.asDate(), it.isCurrent, it.location, it.tripCount)
    } }.distinctUntilChanged()

    private suspend fun validateTripDate(period: PeriodEntity, date: LocalDate) {
        val periods = dao.periods(period.breakerId)
        val start = periods.filter { it.sequence <= period.sequence && it.replacementDay != null }.maxByOrNull { it.sequence }?.replacementDay.asDate()
        val end = periods.filter { it.sequence > period.sequence && it.replacementDay != null }.minByOrNull { it.sequence }?.replacementDay.asDate()
        require(InputRules.isTripDateValid(date, start, end, clock)) {
            "트립일자가 선택한 설치 구간의 날짜 범위를 벗어나거나 미래입니다."
        }
    }

    private fun Long?.asDate(): LocalDate? = this?.let(LocalDate::ofEpochDay)
}
