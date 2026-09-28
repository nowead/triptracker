package com.triptracker.app.presentation

import com.triptracker.app.domain.*
import org.junit.Assert.*
import org.junit.Test

class ManagementHierarchyTest {
    private val a = PanelMaster(id = 1, building = 1, floor = "2", number = "LP")
    private val b = PanelMaster(id = 2, building = 1, floor = "3", number = "LP")
    private val c = PanelMaster(id = 3, building = 2, floor = "2", number = "LP")
    private val d = PanelMaster(id = 4, building = 1, floor = "2", number = "LP-2")
    private val child = BreakerMaster(id = 10, panelId = 1, number = "R1")
    private val state = ManagementState(page = ManagementPage.BREAKERS, panels = listOf(a,b,c,d),
        breakers = listOf(child, child.copy(id = 11, panelId = 2), child.copy(id = 12, panelId = 3), child.copy(id = 13, panelId = 4)))

    @Test fun formPanelsRequireBothParentsAndFilterPanelsUseDraftScope() {
        assertTrue(state.copy(form = MasterForm(MasterFormKind.BREAKER)).formPanels.isEmpty())
        val form = MasterForm(MasterFormKind.BREAKER, building = 1, floor = "2")
        assertEquals(listOf(a,d), state.copy(form = form).formPanels)
        val draft = MasterFilter(building = 1, floor = "2")
        assertEquals(listOf(a,d), state.copy(drafts = mapOf(state.page to draft)).filterPanels)
        assertEquals(4, state.visibleBreakers.size)
    }

    @Test fun selectedPanelIsExactEvenWithDuplicateNumbersAndPartialSearchStillWorks() {
        fun filtered(filter: MasterFilter) = state.copy(filters = mapOf(state.page to filter)).visibleBreakers
        assertEquals(listOf(child), filtered(MasterFilter(panelId = 1)))
        assertEquals(4, filtered(MasterFilter(panel = "lp")).size)
        assertTrue(filtered(MasterFilter(panelId = 999)).isEmpty())
    }

    @Test fun breakerContextOmitsOnlyAppliedCommonFields() {
        fun with(filter: MasterFilter) = state.copy(filters = mapOf(state.page to filter))
        assertEquals("1관 · 2층 · LP", state.breakerContext(child))
        assertEquals("2층 · LP", with(MasterFilter(building = 1)).breakerContext(child))
        assertEquals("LP", with(MasterFilter(building = 1, floor = "2")).breakerContext(child))
        assertEquals("", with(MasterFilter(building = 1, floor = "2", panelId = 1)).breakerContext(child))
        assertEquals("LP", with(MasterFilter(building = 1, floor = "2", panel = "LP")).breakerContext(child))
        assertEquals("1관 · 2층 · LP", state.copy(drafts = mapOf(state.page to MasterFilter(building = 1, floor = "2"))).breakerContext(child))
    }
}
