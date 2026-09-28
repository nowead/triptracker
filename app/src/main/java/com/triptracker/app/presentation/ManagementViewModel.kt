package com.triptracker.app.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.triptracker.app.domain.*
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class ManagementPage(val title: String, val menu: String) {
    PANELS("분전함 마스터", "분전함"), BREAKERS("차단기 마스터", "차단기"),
    TRIPS("차단기 트립이력", "트립이력"), COUNTS("Trip 건수 조회", "건수조회"), LOCATIONS("차단기 위치 조회", "위치조회")
}
enum class MasterFormKind { PANEL, BREAKER, TRIP, REPLACE, PERIOD }
data class MasterForm(
    val kind: MasterFormKind, val id: Long = 0, val building: Int? = null, val floor: String? = null,
    val panelId: Long? = null, val breakerId: Long? = null, val periodId: Long? = null,
    val number: String = "", val location: String = "", val breakerKind: String = "",
    val ports: String = "", val amps: String = "", val load: String = "", val note: String = "",
    val reason: String = "", val date: LocalDate? = null, val unknown: Boolean = false,
    val requestId: String = UUID.randomUUID().toString(), val errors: Map<String, String> = emptyMap(),
)
data class MasterFilter(
    val building: Int? = null, val floor: String? = null, val panel: String = "", val breaker: String = "",
    val panelId: Long? = null, val load: String = "", val tripDate: LocalDate? = null, val scope: PeriodScope = PeriodScope.CURRENT,
) {
    fun matches(key: BreakerKey): Boolean = (building == null || building == key.building) && (floor == null || floor == key.floor) &&
        key.panelNumber.contains(InputRules.normalizeRequiredText(panel)) && key.breakerName.contains(InputRules.normalizeRequiredText(breaker))
}
data class InputSuggestions(
    val panelLocations: List<String> = emptyList(), val kinds: List<String> = emptyList(), val ports: List<String> = emptyList(),
    val amps: List<String> = emptyList(), val loads: List<String> = emptyList(), val tripLocations: List<String> = emptyList(),
    val reasons: List<String> = emptyList(),
)
data class MasterDelete(val kind: MasterFormKind, val ids: Set<Long>, val description: String)
data class ManagementState(
    val page: ManagementPage = ManagementPage.PANELS, val panels: List<PanelMaster> = emptyList(),
    val breakers: List<BreakerMaster> = emptyList(), val trips: List<TripDetail> = emptyList(),
    val counts: List<TripCount> = emptyList(), val loading: Boolean = true, val loadError: String? = null,
    val drafts: Map<ManagementPage, MasterFilter> = emptyMap(), val filters: Map<ManagementPage, MasterFilter> = emptyMap(),
    val form: MasterForm? = null, val formPeriods: List<ReplacementPeriod> = emptyList(),
    val resolving: Boolean = false, val busy: Boolean = false, val message: String? = null,
    val delete: MasterDelete? = null, val selectionMode: Boolean = false, val selected: Set<Long> = emptySet(),
    val selectedPeriodId: Long? = null, val periodPopup: Boolean = false, val tripDetailId: Long? = null,
    val periodBreakerId: Long? = null, val filterError: String? = null, val expandedFilters: Map<ManagementPage, Boolean> = emptyMap(),
) {
    val draft get() = drafts[page] ?: MasterFilter()
    val filter get() = filters[page] ?: MasterFilter()
    val filtersOpen get() = expandedFilters[page] ?: (page != ManagementPage.PANELS && page != ManagementPage.BREAKERS)
    val formPanels get() = form?.takeIf { it.building != null && it.floor != null }?.let { f ->
        panels.filter { it.building == f.building && it.floor == f.floor }
    }.orEmpty()
    val filterPanels get() = panels.filter { (draft.building == null || it.building == draft.building) &&
        (draft.floor == null || it.floor == draft.floor) }
    val selectedFilterPanel get() = panels.firstOrNull { it.id == filter.panelId }
    val filterSummary get() = listOfNotNull(filter.building?.let { "${it}관" }, filter.floor?.let { "${it}층" },
        selectedFilterPanel?.number ?: filter.panel.takeIf { it.isNotBlank() }?.let { "분전함: $it" },
        filter.breaker.takeIf { it.isNotBlank() }?.let { "차단기: $it" },
        filter.load.takeIf { it.isNotBlank() }?.let { "장소: $it" }, filter.tripDate?.toString(),
        if (page == ManagementPage.COUNTS) when (filter.scope) {
            PeriodScope.CURRENT -> "현재 구간"; PeriodScope.PREVIOUS -> "이전 구간"; PeriodScope.ALL -> "전체 구간"
        } else null).joinToString(" · ").ifBlank { "전체" }
    private fun matches(key: BreakerKey): Boolean = filter.matches(key) && (filter.panelId == null || selectedFilterPanel?.let {
        it.building == key.building && it.floor == key.floor && it.number == key.panelNumber
    } == true)
    fun breakerContext(b: BreakerMaster): String = panels.firstOrNull { it.id == b.panelId }?.let { p ->
        listOfNotNull(if (filter.building == p.building) null else "${p.building}관",
            if (filter.floor == p.floor) null else "${p.floor}층", if (filter.panelId == p.id) null else p.number).joinToString(" · ")
    }.orEmpty()
    val countsReady get() = filter.building != null && filter.floor != null
    fun key(b: BreakerMaster): BreakerKey? = panels.firstOrNull { it.id == b.panelId }?.let { BreakerKey(it.building, it.floor, it.number, b.number) }
    val visiblePanels get() = panels.filter { matches(BreakerKey(it.building, it.floor, it.number, "")) }
    val visibleBreakers get() = breakers.filter { key(it)?.let(::matches) == true }
    val visibleTrips get() = trips.filter { matches(it.breaker) && (filter.tripDate == null || filter.tripDate == it.tripDate) }
    val visibleCounts get() = if (!countsReady) emptyList() else counts.filter { matches(it.breaker) && when (filter.scope) {
        PeriodScope.CURRENT -> it.isCurrent; PeriodScope.PREVIOUS -> !it.isCurrent; PeriodScope.ALL -> true
    } }
    val locations get() = breakers.filter { InputRules.normalizeRequiredText(it.load).contains(InputRules.normalizeRequiredText(filter.load)) }
        .mapNotNull { b -> panels.firstOrNull { it.id == b.panelId }?.let { BreakerLocation(it, b) } }
    val selectedPeriod get() = counts.firstOrNull { it.periodId == selectedPeriodId }
    val periodTrips get() = trips.filter { it.periodId == selectedPeriodId }
    val tripDetail get() = trips.firstOrNull { it.id == tripDetailId }
    val suggestions get() = InputSuggestions(
        panels.map { it.location }.options(), breakers.map { it.kind }.options(), breakers.mapNotNull { it.ports?.toString() }.options(),
        breakers.map { it.ratedAmps }.options(), breakers.map { it.load }.options(), trips.map { it.location }.options(), trips.map { it.reason }.options(),
    )
}
private fun List<String>.options() = filter { it.isNotBlank() }.distinct().sorted()

class ManagementViewModel(private val masters: MasterRepository, private val trips: TripRepository, private val clock: Clock) : ViewModel() {
    private val mutable = MutableStateFlow(ManagementState())
    val state = mutable.asStateFlow()
    private var loadingJob: Job? = null
    private var lookup: Job? = null
    init { reload() }
    fun reload() {
        loadingJob?.cancel()
        mutable.update { it.copy(loading = true, loadError = null) }
        loadingJob = viewModelScope.launch {
            try {
                combine(masters.observePanels(), masters.observeBreakers(), trips.observeTrips(), trips.observeCounts(scope = PeriodScope.ALL)) { p, b, t, c ->
                    ManagementState(panels = p, breakers = b, trips = t, counts = c)
                }.collect { data -> mutable.update { it.copy(panels = data.panels, breakers = data.breakers, trips = data.trips, counts = data.counts,
                    loading = false, selected = it.selected.intersect(data.trips.map { row -> row.id }.toSet())) } }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(loading = false, loadError = "데이터를 불러오지 못했습니다. 다시 시도하세요.") } }
        }
    }
    fun navigate(page: ManagementPage) { if (!state.value.busy) {
        lookup?.cancel()
        mutable.update { it.copy(page = page, form = null, selected = emptySet(), selectionMode = false, delete = null,
            selectedPeriodId = null, periodPopup = false, tripDetailId = null, periodBreakerId = null, resolving = false, message = null, filterError = null) }
    } }
    fun back() { if (!state.value.busy) {
        lookup?.cancel()
        mutable.update { when {
            it.delete != null -> it.copy(delete = null)
            it.form != null -> it.copy(form = null, resolving = false, formPeriods = emptyList())
            it.periodPopup -> it.copy(periodPopup = false)
            it.tripDetailId != null -> it.copy(tripDetailId = null)
            it.selectedPeriodId != null -> it.copy(selectedPeriodId = null)
            it.periodBreakerId != null -> it.copy(periodBreakerId = null)
            else -> it.copy(page = ManagementPage.PANELS)
        } }
    } }
    fun clearMessage() { mutable.update { it.copy(message = null) } }
    fun editFilter(change: (MasterFilter) -> MasterFilter) { if (!state.value.busy) mutable.update { s ->
        val changed = change(s.draft)
        val next = when {
            changed.building != s.draft.building -> changed.copy(floor = null, panelId = null, panel = "")
            changed.floor != s.draft.floor -> changed.copy(panelId = null, panel = "")
            changed.panel != s.draft.panel -> changed.copy(panelId = null)
            else -> changed
        }
        s.copy(drafts = s.drafts + (s.page to next), filterError = null)
    } }
    fun chooseFilterPanel(id: Long?) { if (!state.value.busy) mutable.update { s ->
        val p = s.filterPanels.firstOrNull { it.id == id }
        if (id != null && p == null) s else s.copy(filterError = null, drafts = s.drafts + (s.page to s.draft.copy(
            building = p?.building ?: s.draft.building, floor = p?.floor ?: s.draft.floor, panelId = id, panel = "")))
    } }
    fun toggleFilters() { if (!state.value.busy) mutable.update { it.copy(expandedFilters = it.expandedFilters + (it.page to !it.filtersOpen)) } }
    fun search() { if (!state.value.busy) mutable.update {
        if (it.page == ManagementPage.COUNTS && (it.draft.building == null || it.draft.floor == null))
            it.copy(filterError = "관과 층을 선택한 후 조회하세요.", expandedFilters = it.expandedFilters + (it.page to true))
        else it.copy(filterError = null, filters = it.filters + (it.page to it.draft), selected = emptySet(), selectionMode = false,
            expandedFilters = it.expandedFilters + (it.page to false))
    } }
    fun resetSearch() { if (!state.value.busy) mutable.update { it.copy(filterError = null, drafts = it.drafts - it.page, filters = it.filters - it.page, selected = emptySet(), selectionMode = false) } }
    fun newPanel() = open(MasterForm(MasterFormKind.PANEL))
    fun editPanel(p: PanelMaster) = open(MasterForm(MasterFormKind.PANEL, id = p.id, building = p.building, floor = p.floor,
        number = p.number, location = p.location, note = p.note))
    fun newBreaker(panelId: Long? = null) {
        val s = state.value
        val p = s.panels.firstOrNull { it.id == (panelId ?: s.filter.panelId) }
        if (panelId != null && p == null) return
        open(MasterForm(MasterFormKind.BREAKER, building = p?.building ?: s.filter.building,
            floor = p?.floor ?: s.filter.floor, panelId = p?.id))
    }
    fun editBreaker(b: BreakerMaster) {
        val p = state.value.panels.firstOrNull { it.id == b.panelId } ?: return
        open(MasterForm(MasterFormKind.BREAKER, id = b.id, building = p.building, floor = p.floor, panelId = b.panelId, number = b.number,
            breakerKind = b.kind, ports = b.ports?.toString().orEmpty(), amps = b.ratedAmps, load = b.load, note = b.note,
            date = b.installationDate, unknown = b.installationUnknown))
    }
    fun chooseFormBuilding(building: Int) = editForm {
        if (it.building == building) it else it.copy(building = building, floor = null, panelId = null)
    }
    fun chooseFormFloor(floor: String) = editForm {
        if (it.floor == floor) it else it.copy(floor = floor, panelId = null)
    }
    fun chooseFormPanel(id: Long) {
        if (state.value.formPanels.any { it.id == id }) editForm { it.copy(panelId = id) }
    }
    fun newTrip() = open(MasterForm(MasterFormKind.TRIP, date = InputRules.today(clock)))
    fun editTrip(t: TripDetail) {
        val breaker = state.value.breakers.firstOrNull { state.value.key(it) == t.breaker } ?: return
        open(MasterForm(MasterFormKind.TRIP, id = t.id, breakerId = breaker.id, periodId = t.periodId,
            date = t.tripDate, location = t.location, reason = t.reason, note = t.note))
        chooseBreaker(breaker.id, t.periodId)
    }
    fun newReplacement(b: BreakerMaster) = open(MasterForm(MasterFormKind.REPLACE, breakerId = b.id, date = InputRules.today(clock)))
    fun editPeriod(p: TripCount) = open(MasterForm(MasterFormKind.PERIOD, id = p.periodId, date = p.replacementDate, unknown = p.replacementDate == null))
    private fun open(form: MasterForm) { if (!state.value.busy) mutable.update { it.copy(form = form, formPeriods = emptyList(), message = null,
        tripDetailId = null, selectionMode = false, selected = emptySet()) } }
    fun editForm(change: (MasterForm) -> MasterForm) { if (!state.value.busy) mutable.update { s -> s.copy(form = s.form?.let { change(it).copy(errors = emptyMap()) }) } }
    fun chooseBreaker(id: Long, preferred: Long? = null) {
        if (state.value.busy) return
        lookup?.cancel()
        val breaker = state.value.breakers.firstOrNull { it.id == id } ?: return
        val key = state.value.key(breaker) ?: return
        editForm { it.copy(breakerId = id, periodId = null) }
        mutable.update { it.copy(resolving = true, formPeriods = emptyList()) }
        lookup = viewModelScope.launch {
            try {
                val periods = trips.findPeriods(key)
                mutable.update { s -> if (s.form?.breakerId != id) s else s.copy(resolving = false, formPeriods = periods,
                    form = s.form.copy(periodId = periods.firstOrNull { it.id == preferred }?.id ?: periods.firstOrNull { it.isCurrent }?.id)) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(resolving = false, message = "설치 구간을 불러오지 못했습니다. 차단기를 다시 선택하세요.") } }
        }
    }
    fun save() {
        val s = state.value
        val f = s.form ?: return
        if (s.busy || s.resolving) return
        val errors = linkedMapOf<String, String>()
        fun required(key: String, value: String, label: String) { if (value.isBlank()) errors[key] = "$label 입력하세요." }
        when (f.kind) {
            MasterFormKind.PANEL -> {
                if (f.building !in InputRules.buildings) errors["building"] = "관을 선택하세요."
                if (f.floor !in InputRules.floorsFor(f.building)) errors["floor"] = "층을 선택하세요."
                required("number", f.number, "분전함번호를"); required("location", f.location, "분전함위치를")
            }
            MasterFormKind.BREAKER -> {
                if (f.building !in InputRules.buildings) errors["building"] = "관을 선택하세요."
                if (f.floor !in InputRules.floorsFor(f.building)) errors["floor"] = "층을 선택하세요."
                if (s.formPanels.none { it.id == f.panelId }) errors["panel"] = "선택한 관·층의 분전함을 선택하세요."
                required("number", f.number, "차단기번호를"); required("kind", f.breakerKind, "차단기 종류를")
                if ((f.ports.toIntOrNull() ?: 0) <= 0) errors["ports"] = "양의 정수를 입력하세요."
                if ((f.amps.toBigDecimalOrNull()?.signum() ?: 0) <= 0) errors["amps"] = "양의 A 값을 입력하세요."
                required("load", f.load, "장소(부하)를")
            }
            MasterFormKind.TRIP -> {
                if (s.breakers.none { it.id == f.breakerId }) errors["breaker"] = "차단기를 선택하세요."
                if (s.formPeriods.none { it.id == f.periodId }) errors["period"] = "설치 구간을 선택하세요."
                required("location", f.location, "트립장소를"); required("reason", f.reason, "트립사유를")
            }
            else -> Unit
        }
        if (f.kind != MasterFormKind.PANEL) {
            val allowUnknown = f.kind == MasterFormKind.BREAKER || f.kind == MasterFormKind.PERIOD
            if (f.date == null && !(allowUnknown && f.unknown)) errors["date"] = "날짜를 선택하세요."
            if (f.date?.isAfter(InputRules.today(clock)) == true) errors["date"] = "미래 날짜는 입력할 수 없습니다."
        }
        if (errors.isNotEmpty()) { mutable.update { it.copy(form = f.copy(errors = errors)) }; return }
        perform(closeForm = true) {
            when (f.kind) {
                MasterFormKind.PANEL -> masters.savePanel(PanelMaster(f.id, requireNotNull(f.building), requireNotNull(f.floor), f.number, f.location, f.note))
                MasterFormKind.BREAKER -> masters.saveBreaker(BreakerMaster(f.id, requireNotNull(f.panelId), f.number, f.breakerKind,
                    f.ports.toInt(), f.amps, f.load, f.note, f.date, f.unknown))
                MasterFormKind.TRIP -> {
                    val b = requireNotNull(s.breakers.firstOrNull { it.id == f.breakerId })
                    val request = TripRegistration(requireNotNull(s.key(b)), requireNotNull(f.date), f.location, f.note,
                        periodId = requireNotNull(f.periodId), requestId = f.requestId, reason = f.reason)
                    masters.saveMasterTrip(f.id.takeIf { it != 0L }, request)
                }
                MasterFormKind.REPLACE -> masters.replaceBreaker(requireNotNull(f.breakerId), requireNotNull(f.date))
                MasterFormKind.PERIOD -> masters.correctInstallation(f.id, f.date)
            }
        }
    }
    private fun perform(closeForm: Boolean = false, action: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                action()
                mutable.update { it.copy(busy = false, form = if (closeForm) null else it.form, delete = null, message = "처리 완료") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(busy = false,
                message = if (e is IllegalArgumentException) e.message else "저장하지 못했습니다. 입력 내용을 확인하고 다시 시도하세요.") } }
        }
    }
    fun requestDeletePanel(p: PanelMaster) = confirm(MasterDelete(MasterFormKind.PANEL, setOf(p.id), "${p.label()} · 분전함 1건"))
    fun requestDeleteBreaker(b: BreakerMaster) = confirm(MasterDelete(MasterFormKind.BREAKER, setOf(b.id), "${state.value.key(b)?.label()} · 차단기 1건\n트립 이력이 있으면 삭제할 수 없습니다."))
    fun requestDeleteTrips(ids: Set<Long>) {
        val rows = state.value.trips.filter { it.id in ids }
        if (!state.value.busy) mutable.update { it.copy(tripDetailId = null) }
        if (rows.isNotEmpty()) confirm(MasterDelete(MasterFormKind.TRIP, rows.map { it.id }.toSet(),
            "트립 ${rows.size}건\n" + rows.joinToString("\n") { "#${it.id} · ${it.breaker.label()} · ${it.tripDate}" }))
    }
    fun requestDeletePeriod(p: TripCount) {
        if (state.value.busy) return
        viewModelScope.launch {
            try {
                val periods = trips.findPeriods(p.breaker)
                val restore = if (p.isCurrent) periods.filter { it.id != p.periodId }.maxByOrNull { it.sequence } else null
                val restoredCount = state.value.counts.firstOrNull { it.periodId == restore?.id }
                confirm(MasterDelete(MasterFormKind.PERIOD, setOf(p.periodId), "${p.breaker.label()} · 설치 구간 #${p.periodId} 1건" +
                    (if (restore != null) "\n삭제 후 구간 #${restore.id} (${restore.replacementDate ?: "설치일 미상"}), ${restoredCount?.tripCount ?: 0}건을 현재로 복원합니다." else "") +
                    "\n트립이 있거나 유일한 구간이면 삭제할 수 없습니다."))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(message = "설치 구간을 확인하지 못했습니다.") } }
        }
    }
    private fun confirm(target: MasterDelete) { if (!state.value.busy) mutable.update { it.copy(delete = target) } }
    fun cancelDelete() { if (!state.value.busy) mutable.update { it.copy(delete = null) } }
    fun deleteConfirmed() {
        val target = state.value.delete ?: return
        perform {
            when (target.kind) {
                MasterFormKind.PANEL -> masters.deletePanel(target.ids.single())
                MasterFormKind.BREAKER -> masters.deleteBreaker(target.ids.single())
                MasterFormKind.TRIP -> trips.deleteTrips(target.ids)
                MasterFormKind.PERIOD -> masters.deleteInstallation(target.ids.single())
                MasterFormKind.REPLACE -> Unit
            }
            mutable.update { it.copy(selected = it.selected - target.ids, tripDetailId = null) }
        }
    }
    fun toggleSelectionMode() { if (!state.value.busy) mutable.update { it.copy(selectionMode = !it.selectionMode, selected = emptySet()) } }
    fun toggleTrip(id: Long) { if (!state.value.busy) mutable.update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) } }
    fun showTrip(id: Long?) { mutable.update { it.copy(tripDetailId = id) } }
    fun showCount(id: Long) { mutable.update { it.copy(selectedPeriodId = id, periodPopup = false) } }
    fun showPeriodPopup(show: Boolean) { mutable.update { it.copy(periodPopup = show) } }
    fun showPeriods(id: Long) { mutable.update { it.copy(periodBreakerId = id) } }
}

internal fun PanelMaster.label() = "${building}관 ${floor}층 · $number"
internal fun BreakerKey.label() = "${building}관 ${floor}층 · $panelNumber / $breakerName"
internal fun String.entered() = ifBlank { "미입력" }
