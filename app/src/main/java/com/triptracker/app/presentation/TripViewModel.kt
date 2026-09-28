package com.triptracker.app.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.triptracker.app.domain.*
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Page { HOME, REGISTER, HISTORY, COUNTS }
enum class FormField { BUILDING, FLOOR, PANEL, BREAKER, LOCATION, REPLACEMENT, TRIP_DATE }
data class TripForm(
    val building: Int? = null, val floor: String? = null, val panelNumber: String = "",
    val breakerName: String = "", val location: String = "", val note: String = "",
    val tripDate: LocalDate, val replacementDate: LocalDate? = null, val replacementUnknown: Boolean = false,
    val periodId: Long? = null,
) {
    fun keyOrNull(): BreakerKey? = if (building != null && floor != null &&
        InputRules.isRequiredTextValid(panelNumber) && InputRules.isRequiredTextValid(breakerName)) {
        BreakerKey(building, floor, InputRules.normalizeRequiredText(panelNumber), InputRules.normalizeRequiredText(breakerName))
    } else null
}
data class TripUiState(
    val form: TripForm, val page: Page = Page.HOME,
    val homeCounts: List<TripCount> = emptyList(), val homeBuilding: Int? = null,
    val loadingHome: Boolean = true, val homeError: String? = null,
    val expandedPanels: Set<PanelKey> = emptySet(), val exactBreaker: BreakerKey? = null,
    val selectionMode: Boolean = false,
    val periods: List<ReplacementPeriod> = emptyList(), val resolving: Boolean = false,
    val resolvedKey: BreakerKey? = null, val saving: Boolean = false,
    val errors: Map<FormField, String> = emptyMap(), val validationAttempt: Int = 0,
    val message: String? = null, val searchDraft: TripSearch = TripSearch(),
    val appliedSearch: TripSearch = TripSearch(), val scope: PeriodScope = PeriodScope.CURRENT,
    val scopeDraft: PeriodScope = PeriodScope.CURRENT,
    val trips: List<TripDetail> = emptyList(), val counts: List<TripCount> = emptyList(),
    val loadingTrips: Boolean = true, val loadingCounts: Boolean = true,
    val queryError: String? = null, val detail: TripDetail? = null,
    val editingId: Long? = null, val selectedIds: Set<Long> = emptySet(),
    val deleteConfirmation: List<TripDetail>? = null, val deleting: Boolean = false,
) {
    val homePanels: List<PanelSummary> get() = dashboardPanels(homeCounts, homeBuilding)
}

class TripViewModel(private val repository: TripRepository, private val clock: Clock) : ViewModel() {
    private val mutableState = MutableStateFlow(TripUiState(TripForm(tripDate = InputRules.today(clock))))
    val state = mutableState.asStateFlow()
    private var lookup: Job? = null
    private var tripsJob: Job? = null
    private var countsJob: Job? = null
    private var homeJob: Job? = null
    private var requestId = UUID.randomUUID().toString()
    init { loadResults(); loadHome() }

    fun loadHome() {
        homeJob?.cancel()
        mutableState.update { it.copy(loadingHome = true, homeError = null) }
        homeJob = viewModelScope.launch {
            try { repository.observeCounts(scope = PeriodScope.CURRENT).collect { rows ->
                mutableState.update { it.copy(homeCounts = rows, loadingHome = false) }
            } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(loadingHome = false, homeError = "현황을 불러오지 못했습니다.") } }
        }
    }
    fun selectBuilding(building: Int?) {
        if (building == null || building in InputRules.buildings) mutableState.update { it.copy(homeBuilding = building) }
    }
    fun togglePanel(key: PanelKey) { mutableState.update {
        it.copy(expandedPanels = if (key in it.expandedPanels) it.expandedPanels - key else it.expandedPanels + key)
    } }
    fun openBreaker(key: BreakerKey) {
        if (state.value.saving || state.value.deleting) return
        val search = TripSearch(building = key.building, floor = key.floor, panelNumber = key.panelNumber, breakerName = key.breakerName)
        mutableState.update { it.copy(page = Page.HISTORY, exactBreaker = key, detail = null,
            appliedSearch = search, searchDraft = search, selectedIds = emptySet(), selectionMode = false,
            deleteConfirmation = null, message = null) }
        loadResults()
    }
    fun toggleSelectionMode() {
        if (state.value.deleting || state.value.deleteConfirmation != null) return
        mutableState.update { it.copy(selectionMode = !it.selectionMode, selectedIds = emptySet()) }
    }

    fun showPage(page: Page) {
        if (state.value.saving || state.value.deleting) return
        if (state.value.editingId != null && page != Page.REGISTER) cancelEdit()
        val leaveBreaker = page == Page.HISTORY && state.value.exactBreaker != null
        mutableState.update { it.copy(page = page, detail = null, message = null, selectionMode = false, selectedIds = emptySet(), deleteConfirmation = null) }
        if (leaveBreaker) resetSearch()
    }
    fun showDetail(trip: TripDetail?) { if (!state.value.deleting) mutableState.update { it.copy(detail = trip, message = null) } }
    fun clearMessage() { mutableState.update { it.copy(message = null) } }

    fun startEdit(trip: TripDetail) {
        if (state.value.saving || state.value.deleting) return
        lookup?.cancel()
        requestId = UUID.randomUUID().toString()
        mutableState.update { it.copy(page = Page.REGISTER, detail = null, editingId = trip.id, message = null,
            selectedIds = emptySet(), selectionMode = false, deleteConfirmation = null, errors = emptyMap(),
            form = TripForm(building = trip.breaker.building, floor = trip.breaker.floor,
                panelNumber = trip.breaker.panelNumber, breakerName = trip.breaker.breakerName,
                location = trip.location, note = trip.note, tripDate = trip.tripDate, periodId = trip.periodId)) }
        resolveKey(trip.periodId)
    }

    fun cancelEdit() {
        if (state.value.saving) return
        lookup?.cancel()
        requestId = UUID.randomUUID().toString()
        mutableState.update { it.copy(page = Page.HISTORY, editingId = null, errors = emptyMap(), message = null,
            form = TripForm(tripDate = InputRules.today(clock)), periods = emptyList(), resolvedKey = null, resolving = false) }
    }

    fun toggleSelection(id: Long) {
        if (!state.value.selectionMode || state.value.deleting || state.value.deleteConfirmation != null || state.value.trips.none { it.id == id }) return
        mutableState.update { it.copy(selectedIds = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id) }
    }
    fun requestDelete() {
        if (state.value.saving || state.value.deleting) return
        val selected = state.value.trips.filter { it.id in state.value.selectedIds }
        if (selected.isNotEmpty()) mutableState.update { it.copy(deleteConfirmation = selected, message = null) }
    }
    fun cancelDelete() { if (!state.value.deleting) mutableState.update { it.copy(deleteConfirmation = null) } }
    fun confirmDelete() {
        val snapshot = state.value
        if (snapshot.deleting) return
        val ids = snapshot.deleteConfirmation?.map { it.id }?.toSet() ?: return
        mutableState.update { it.copy(deleting = true) }
        viewModelScope.launch {
            try {
                val deleted = repository.deleteTrips(ids)
                mutableState.update { it.copy(deleting = false, deleteConfirmation = null, selectedIds = it.selectedIds - ids,
                    detail = it.detail?.takeUnless { row -> row.id in ids }, message = "이력 ${deleted}건 삭제 완료") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutableState.update { it.copy(deleting = false, deleteConfirmation = null,
                    message = "삭제하지 못했습니다. 선택한 이력을 확인하고 다시 시도해 주세요.") }
            }
        }
    }

    fun editForm(change: (TripForm) -> TripForm) {
        if (state.value.saving) return
        val previous = state.value.form
        var next = change(previous)
        if (next == previous) return
        requestId = UUID.randomUUID().toString()
        val identityChanged = previous.keyOrNull() != next.keyOrNull()
        if (identityChanged) next = next.copy(periodId = null, replacementDate = null, replacementUnknown = false)
        mutableState.update { it.copy(form = next, errors = emptyMap()) }
        if (identityChanged) resolveKey()
    }

    fun resolveKey(preferredPeriodId: Long? = null) {
        lookup?.cancel()
        val key = state.value.form.keyOrNull()
        mutableState.update { it.copy(periods = emptyList(), resolvedKey = null, resolving = key != null,
            form = it.form.copy(periodId = null)) }
        if (key == null) return
        lookup = viewModelScope.launch {
            try {
                val periods = repository.findPeriods(key)
                if (state.value.form.keyOrNull() == key) mutableState.update {
                    it.copy(periods = periods, resolvedKey = key, resolving = false,
                        form = it.form.copy(periodId = periods.firstOrNull { p -> p.id == preferredPeriodId }?.id
                            ?: periods.firstOrNull { p -> p.isCurrent }?.id))
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(resolving = false, message = "차단기 정보를 불러오지 못했습니다. 다시 확인해 주세요.") } }
        }
    }

    fun save() {
        val snapshot = state.value
        if (snapshot.saving) return
        val form = snapshot.form
        val errors = linkedMapOf<FormField, String>()
        if (form.building !in InputRules.buildings) errors[FormField.BUILDING] = "관을 선택하세요."
        if (form.floor !in InputRules.floorsFor(form.building)) errors[FormField.FLOOR] = "층을 선택하세요."
        if (!InputRules.isRequiredTextValid(form.panelNumber)) errors[FormField.PANEL] = "분전함번호를 입력하세요."
        if (!InputRules.isRequiredTextValid(form.breakerName)) errors[FormField.BREAKER] = "차단기명을 입력하세요."
        if (!InputRules.isRequiredTextValid(form.location)) errors[FormField.LOCATION] = "장소를 입력하세요."
        val key = form.keyOrNull()
        if (key != null && snapshot.resolvedKey != key) errors[FormField.REPLACEMENT] = "차단기 정보를 확인해 주세요."
        val selected = snapshot.periods.firstOrNull { it.id == form.periodId }
        if (snapshot.periods.isNotEmpty() && selected == null) errors[FormField.REPLACEMENT] = "교체 구간을 선택하세요."
        if (snapshot.periods.isEmpty() && form.replacementDate == null && !form.replacementUnknown)
            errors[FormField.REPLACEMENT] = "마지막 교체일자 또는 교체일 미상을 선택하세요."
        if (form.replacementDate?.isAfter(InputRules.today(clock)) == true)
            errors[FormField.REPLACEMENT] = "미래 교체일자는 입력할 수 없습니다."
        val start = selected?.replacementDate ?: if (snapshot.periods.isEmpty()) form.replacementDate else null
        val end = selected?.let { p -> snapshot.periods.filter { it.sequence > p.sequence }.minByOrNull { it.sequence }?.replacementDate }
        if (!InputRules.isTripDateValid(form.tripDate, start, end, clock))
            errors[FormField.TRIP_DATE] = "오늘까지의 날짜 중 선택한 교체 구간에 해당하는 날짜를 입력하세요."
        mutableState.update { it.copy(errors = errors, validationAttempt = it.validationAttempt + 1) }
        if (errors.isNotEmpty() || key == null) return
        val registration = TripRegistration(key, form.tripDate, form.location, form.note, form.periodId,
            form.replacementDate, form.replacementUnknown, requestId)
        mutableState.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            try {
                val id = if (snapshot.editingId == null) repository.registerTrip(registration)
                    else { repository.updateTrip(snapshot.editingId, registration); snapshot.editingId }
                requestId = UUID.randomUUID().toString()
                mutableState.update { it.copy(saving = false, page = Page.HISTORY, editingId = null,
                    message = "이력 #$id ${if (snapshot.editingId == null) "저장" else "수정"} 완료",
                    form = TripForm(tripDate = InputRules.today(clock)), periods = emptyList(), resolvedKey = null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutableState.update { it.copy(saving = false, message = if (e is IllegalArgumentException) e.message
                    else "저장하지 못했습니다. 입력 내용은 유지됩니다. 다시 시도해 주세요.") }
            }
        }
    }

    fun editSearch(change: (TripSearch) -> TripSearch) { mutableState.update { it.copy(searchDraft = change(it.searchDraft)) } }
    fun editScope(scope: PeriodScope) { mutableState.update { it.copy(scopeDraft = scope) } }
    fun search() {
        if (state.value.deleting) return
        mutableState.update { it.copy(appliedSearch = it.searchDraft, scope = it.scopeDraft, detail = null, exactBreaker = null, selectionMode = false,
            selectedIds = emptySet(), deleteConfirmation = null) }
        loadResults()
    }
    fun resetSearch() {
        if (state.value.deleting) return
        mutableState.update { it.copy(searchDraft = TripSearch(), appliedSearch = TripSearch(), exactBreaker = null, selectionMode = false,
            scope = PeriodScope.CURRENT, scopeDraft = PeriodScope.CURRENT, detail = null,
            selectedIds = emptySet(), deleteConfirmation = null) }
        loadResults()
    }
    private fun loadResults() {
        tripsJob?.cancel(); countsJob?.cancel()
        val search = state.value.appliedSearch
        val scope = state.value.scope
        val exactBreaker = state.value.exactBreaker
        mutableState.update { it.copy(loadingTrips = true, loadingCounts = true, queryError = null, trips = emptyList(), counts = emptyList()) }
        tripsJob = viewModelScope.launch {
            try { repository.observeTrips(search).collect { found ->
                val rows = if (exactBreaker == null) found else found.filter { it.breaker == exactBreaker }
                mutableState.update {
                it.copy(trips = rows, loadingTrips = false, selectedIds = it.selectedIds.intersect(rows.map { row -> row.id }.toSet()))
            } } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(loadingTrips = false, queryError = "이력을 불러오지 못했습니다. 다시 조회해 주세요.") } }
        }
        countsJob = viewModelScope.launch {
            try { repository.observeCounts(search, scope).collect { rows -> mutableState.update { it.copy(counts = rows, loadingCounts = false) } } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(loadingCounts = false, queryError = "횟수를 불러오지 못했습니다. 다시 조회해 주세요.") } }
        }
    }
}
