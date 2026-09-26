package com.triptracker.app.presentation
import androidx.lifecycle.viewModelScope

import com.triptracker.app.domain.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class TripViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-09-15T01:00:00Z"), ZoneId.of("Asia/Seoul"))
    private lateinit var fake: FakeRepository
    private lateinit var vm: TripViewModel
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        fake = FakeRepository()
        vm = TripViewModel(fake, clock)
    }
    @After fun teardown() { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    private fun fill() {
        vm.editForm { it.copy(building = 1, floor = "3", panelNumber = "lp-3-a", breakerName = "r1", location = "사무실") }
    }

    @Test fun blankFormShowsFieldErrorsAndTodayDefault() = runTest(dispatcher) {
        assertEquals(LocalDate.of(2026, 9, 15), vm.state.value.form.tripDate)
        vm.save()
        assertTrue(vm.state.value.errors.containsKey(FormField.BUILDING))
        assertTrue(fake.saved.isEmpty())
    }

    @Test fun unknownMustBeExplicitAndSuccessfulSaveOpensHistory() = runTest(dispatcher) {
        fill(); advanceUntilIdle()
        vm.save()
        assertTrue(vm.state.value.errors.containsKey(FormField.REPLACEMENT))
        vm.editForm { it.copy(replacementUnknown = true) }
        vm.save(); vm.save(); advanceUntilIdle()
        assertEquals(1, fake.saved.size)
        assertEquals(Page.HISTORY, vm.state.value.page)
        assertTrue(fake.saved.single().replacementUnknown)
        assertEquals("", vm.state.value.form.location)
    }

    @Test fun existingPeriodDefaultsCurrentAndChangingIdentityClearsIt() = runTest(dispatcher) {
        fake.periods = listOf(ReplacementPeriod(9, null, 2, true), ReplacementPeriod(8, null, 1, false))
        fill(); advanceUntilIdle()
        assertEquals(9L, vm.state.value.form.periodId)
        vm.editForm { it.copy(periodId = 8) }
        vm.editForm { it.copy(panelNumber = "") }
        assertNull(vm.state.value.form.periodId)
        assertTrue(vm.state.value.periods.isEmpty())
    }

    @Test fun failedSaveKeepsDraftAndRetryToken() = runTest(dispatcher) {
        fill(); advanceUntilIdle()
        vm.editForm { it.copy(replacementUnknown = true, note = "keep") }
        fake.fail = true
        vm.save(); advanceUntilIdle()
        val request = fake.saved.single()
        assertEquals("keep", vm.state.value.form.note)
        assertFalse(vm.state.value.saving)
        fake.fail = false
        vm.save(); advanceUntilIdle()
        assertEquals(request.requestId, fake.saved.last().requestId)
    }

    @Test fun searchDraftIsNotAppliedUntilSearchAndResetRestoresDefaults() = runTest(dispatcher) {
        advanceUntilIdle()
        vm.editSearch { it.copy(panelNumber = "LP") }
        assertEquals("", vm.state.value.appliedSearch.panelNumber)
        vm.search(); advanceUntilIdle()
        assertEquals("LP", vm.state.value.appliedSearch.panelNumber)
        vm.resetSearch(); advanceUntilIdle()
        assertEquals(TripSearch(), vm.state.value.appliedSearch)
        assertEquals(PeriodScope.CURRENT, vm.state.value.scope)
    }

    private fun row(id: Long = 1) = TripDetail(id, 8, BreakerKey(1, "3", "LP", "R1"),
        LocalDate.of(2026, 9, 15), null, "사무실", "original", clock.instant())

    @Test fun editingKeepsOriginalPastPeriodAndSearchConditions() = runTest(dispatcher) {
        fake.periods = listOf(ReplacementPeriod(9, null, 2, true), ReplacementPeriod(8, null, 1, false))
        vm.editSearch { it.copy(panelNumber = "LP") }; vm.search()
        vm.startEdit(row()); advanceUntilIdle()
        assertEquals(1L, vm.state.value.editingId)
        assertEquals(8L, vm.state.value.form.periodId)
        assertEquals("original", vm.state.value.form.note)
        vm.editForm { it.copy(location = "new") }
        vm.save(); advanceUntilIdle()
        assertEquals(1L, fake.updated.single().first)
        assertTrue(fake.saved.isEmpty())
        assertEquals("LP", vm.state.value.appliedSearch.panelNumber)
        assertNull(vm.state.value.editingId)
    }

    @Test fun deleteRequiresConfirmationAndSearchClearsSelection() = runTest(dispatcher) {
        fake.rows.value = listOf(row(), row(2)); advanceUntilIdle()
        vm.toggleSelectionMode()
        vm.toggleSelection(1); vm.requestDelete()
        assertTrue(fake.deleted.isEmpty())
        vm.cancelDelete(); assertTrue(fake.deleted.isEmpty())
        vm.editSearch { it.copy(panelNumber = "OTHER") }; vm.search(); advanceUntilIdle()
        assertTrue(vm.state.value.selectedIds.isEmpty())
        assertNull(vm.state.value.deleteConfirmation)
        vm.confirmDelete(); advanceUntilIdle()
        assertTrue(fake.deleted.isEmpty())
    }

    @Test fun confirmedDeleteRunsOnceAndFailurePreservesSelection() = runTest(dispatcher) {
        fake.rows.value = listOf(row(), row(2)); advanceUntilIdle()
        vm.toggleSelectionMode()
        vm.toggleSelection(1); vm.toggleSelection(2); vm.requestDelete()
        fake.fail = true; vm.confirmDelete(); advanceUntilIdle()
        assertEquals(setOf(1L, 2L), vm.state.value.selectedIds)
        assertFalse(vm.state.value.deleting)
        fake.fail = false; vm.requestDelete(); vm.confirmDelete(); vm.confirmDelete(); advanceUntilIdle()
        assertEquals(listOf(setOf(1L, 2L)), fake.deleted)
        assertTrue(vm.state.value.selectedIds.isEmpty())
    }

    @Test fun cancellingEditCannotTurnItIntoAnAccidentalRegistration() = runTest(dispatcher) {
        fake.periods = listOf(ReplacementPeriod(8, null, 1, true))
        vm.startEdit(row()); advanceUntilIdle()
        vm.cancelEdit()
        assertNull(vm.state.value.editingId)
        assertEquals(Page.HISTORY, vm.state.value.page)
        vm.showPage(Page.REGISTER)
        assertEquals("", vm.state.value.form.location)
        assertTrue(fake.updated.isEmpty())
    }

    @Test fun dashboardGroupsByBuildingFloorAndPanelAndIncludesZeroCounts() = runTest(dispatcher) {
        fun count(id: Long, building: Int, floor: String, panel: String, trips: Long) =
            TripCount(id, BreakerKey(building, floor, panel, "R$id"), null, true, null, trips)
        fake.countRows.value = listOf(count(1, 1, "3", "LP", 2), count(2, 1, "3", "LP", 3),
            count(3, 2, "3", "LP", 1), count(4, 1, "B1", "LP", 0))
        advanceUntilIdle()
        assertEquals(Page.HOME, vm.state.value.page)
        val panels = vm.state.value.homePanels
        assertEquals(listOf("B1", "3", "3"), panels.map { it.key.floor })
        assertEquals(listOf(0L, 5L, 1L), panels.map { it.tripCount })
        vm.selectBuilding(2)
        assertEquals(2, vm.state.value.homePanels.single().key.building)
        vm.selectBuilding(null)
        assertEquals(3, vm.state.value.homePanels.size)
    }

    @Test fun dashboardIsIndependentOfSearchAndUpdatesAfterCountChanges() = runTest(dispatcher) {
        fake.countRows.value = listOf(TripCount(8, row().breaker, null, true, "사무실", 2))
        advanceUntilIdle()
        vm.editSearch { it.copy(building = 4) }; vm.search(); advanceUntilIdle()
        assertTrue(vm.state.value.counts.isEmpty())
        assertEquals(2L, vm.state.value.homePanels.single().tripCount)
        fake.countRows.value = fake.countRows.value.map { it.copy(tripCount = 0) }
        advanceUntilIdle()
        assertEquals(0L, vm.state.value.homePanels.single().tripCount)
    }

    @Test fun breakerDrillDownMatchesExactIdentityAndKeepsAllPeriods() = runTest(dispatcher) {
        fake.rows.value = listOf(row(), row(2).copy(periodId = 9),
            row(3).copy(breaker = row().breaker.copy(breakerName = "R10")),
            row(4).copy(breaker = row().breaker.copy(panelNumber = "LP-X")))
        vm.openBreaker(row().breaker); advanceUntilIdle()
        assertEquals(listOf(1L, 2L), vm.state.value.trips.map { it.id })
        assertEquals(Page.HISTORY, vm.state.value.page)
        vm.showDetail(vm.state.value.trips.first()); vm.showDetail(null)
        assertEquals(row().breaker, vm.state.value.exactBreaker)
        vm.resetSearch(); advanceUntilIdle()
        assertNull(vm.state.value.exactBreaker)
        assertEquals(4, vm.state.value.trips.size)
    }

    @Test fun selectionIsExplicitAndCancellingOrNavigatingClearsIt() = runTest(dispatcher) {
        fake.rows.value = listOf(row()); advanceUntilIdle()
        vm.toggleSelection(1)
        assertTrue(vm.state.value.selectedIds.isEmpty())
        vm.toggleSelectionMode(); vm.toggleSelection(1)
        assertEquals(setOf(1L), vm.state.value.selectedIds)
        vm.toggleSelectionMode()
        assertTrue(vm.state.value.selectedIds.isEmpty())
        vm.toggleSelectionMode(); vm.toggleSelection(1); vm.showPage(Page.HOME)
        assertFalse(vm.state.value.selectionMode)
        assertTrue(vm.state.value.selectedIds.isEmpty())
    }

    private class FakeRepository : TripRepository {
        val countRows = MutableStateFlow<List<TripCount>>(emptyList())
        val updated = mutableListOf<Pair<Long, TripRegistration>>()
        val deleted = mutableListOf<Set<Long>>()
        val rows = MutableStateFlow<List<TripDetail>>(emptyList())
        override suspend fun updateTrip(id: Long, registration: TripRegistration) { updated += id to registration }
        override suspend fun deleteTrips(ids: Set<Long>): Int {
            if (fail) error("Storage unavailable")
            deleted += ids
            rows.value = rows.value.filterNot { it.id in ids }
            return ids.size
        }
        val saved = mutableListOf<TripRegistration>()
        var fail = false
        var periods = emptyList<ReplacementPeriod>()
        override suspend fun registerTrip(registration: TripRegistration): Long {
            saved += registration
            if (fail) error("Storage unavailable")
            return saved.size.toLong()
        }
        override suspend fun addTrip(trip: NewTrip): Long = error("unused")
        override suspend fun findPeriods(breaker: BreakerKey) = periods
        override fun observeTrips(search: TripSearch): Flow<List<TripDetail>> = rows
        override fun observeCounts(search: TripSearch, scope: PeriodScope): Flow<List<TripCount>> =
            countRows.map { rows -> rows.filter { search.building == null || it.breaker.building == search.building } }
    }
}
