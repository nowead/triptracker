package com.triptracker.app.presentation

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.triptracker.app.data.local.TripDatabase
import com.triptracker.app.data.repository.RoomTripRepository
import com.triptracker.app.domain.*
import java.time.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ManagementScreenTest {
    @get:Rule val compose = createComposeRule()
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), TripDatabase::class.java).build()
    private val clock = Clock.fixed(Instant.parse("2026-09-27T01:00:00Z"), ZoneId.of("Asia/Seoul"))
    private val repo = RoomTripRepository(db, clock)
    private lateinit var model: ManagementViewModel
    @After fun close() { if (::model.isInitialized) model.viewModelScope.cancel(); db.close() }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Config(sdk = [28], qualifiers = "w360dp-h800dp-xhdpi")
    @Test fun largeTextKeepsNavigationAndRequiredFieldsReachable() {
        model = ManagementViewModel(repo, repo, clock)
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(density.density, fontScale = 1.3f)) { ManagementScreen(model) }
        }
        compose.onNodeWithText("분전함 마스터").assertIsDisplayed()
        compose.onNodeWithText("새 분전함").performClick()
        compose.onNodeWithTag("management-form").performScrollToNode(hasText("저장", substring = false))
        compose.onNodeWithText("저장", substring = false).performClick()
        compose.onNodeWithText("관을 선택하세요.").assertIsDisplayed()
        compose.onNodeWithText("관: 선택").performClick()
        compose.onNodeWithText("1관", substring = false).performClick()
        compose.onNodeWithTag("management-form").performScrollToNode(hasText("특기사항 (선택)"))
        compose.onNodeWithText("특기사항 (선택)").assertIsDisplayed()
        compose.onNodeWithText("목록으로").performClick()
        compose.onNodeWithText("위치조회", substring = false).assertIsDisplayed().performClick()
        compose.onNodeWithText("차단기 위치 조회").assertIsDisplayed()
        val screenshot = java.io.File("build/reports/ui/management-large-text.png")
        screenshot.parentFile?.mkdirs()
        compose.runOnIdle {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).single()
            val view = activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            screenshot.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun masterActionsRequireOverflowAndDeleteStillNeedsConfirmation() {
        runBlocking {
            val p = repo.savePanel(PanelMaster(building = 1, floor = "3", number = "LP", location = "복도", note = "전체 특기사항"))
            repo.saveBreaker(BreakerMaster(panelId = p, number = "R1", kind = "MCCB", ports = 2, ratedAmps = "20", load = "사무실"))
        }
        model = ManagementViewModel(repo, repo, clock)
        compose.setContent { ManagementScreen(model) }
        compose.waitUntil(5_000) { compose.runOnIdle { model.state.value.breakers.size == 1 } }
        compose.onNodeWithText("수정", substring = false).assertDoesNotExist()
        compose.onNodeWithText("삭제", substring = false).assertDoesNotExist()
        compose.onNodeWithTag("panel-menu-1").performClick()
        compose.onNodeWithText("상세 보기").performClick()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithText("닫기", substring = false).performClick()
        compose.onNodeWithTag("panel-menu-1").performClick()
        compose.onNodeWithText("수정", substring = false).performClick()
        assertEquals(MasterFormKind.PANEL, model.state.value.form!!.kind)
        compose.onNodeWithText("목록으로").performClick()
        compose.onNodeWithText("차단기", substring = false).performClick()
        compose.onNodeWithText("수정", substring = false).assertDoesNotExist()
        compose.onNodeWithText("삭제", substring = false).assertDoesNotExist()
        compose.onNodeWithTag("breaker-menu-1").performClick()
        compose.onNodeWithText("삭제", substring = false).performClick()
        compose.onNodeWithText("삭제 확인").assertIsDisplayed()
        compose.onNodeWithText("취소", substring = false).performClick()
        assertEquals(1, model.state.value.breakers.size)
        compose.onNodeWithTag("breaker-menu-1").performClick()
        compose.onNodeWithText("설치 구간", substring = false).performClick()
        assertEquals(1L, model.state.value.periodBreakerId)
    }

    @Test fun panelClickPrefillsChildAndChangingParentsClearsInvalidSelection() {
        runBlocking {
            repo.savePanel(PanelMaster(building = 1, floor = "2", number = "LP-A", location = "복도"))
            repo.savePanel(PanelMaster(building = 2, floor = "3", number = "LP-B", location = "계단"))
        }
        model = ManagementViewModel(repo, repo, clock)
        compose.setContent { ManagementScreen(model) }
        compose.waitUntil(5_000) { compose.runOnIdle { model.state.value.panels.size == 2 } }
        compose.onNodeWithTag("panel-open-1").performClick()
        compose.onNodeWithText("관: 1관").assertIsDisplayed()
        compose.onNodeWithText("층: 2").assertIsDisplayed()
        assertEquals(1L, model.state.value.form!!.panelId)
        compose.onNodeWithText("관: 1관").performClick(); compose.onNodeWithText("2관", substring = false).performClick()
        assertNull(model.state.value.form!!.floor)
        assertNull(model.state.value.form!!.panelId)
        compose.onNodeWithText("층: 선택").performClick()
        compose.onNodeWithTag("choices").performScrollToNode(hasText("3")); compose.onNodeWithText("3", substring = false).performClick()
        compose.onNodeWithText("분전함: 선택").performClick()
        compose.onNodeWithText("1관 2층 · LP-A").assertDoesNotExist()
        compose.onNodeWithText("2관 3층 · LP-B").performClick()
        fun input(label: String, value: String) {
            compose.onNodeWithTag("management-form").performScrollToNode(hasText(label))
            compose.onNodeWithText(label).performTextInput(value)
        }
        input("차단기번호", "R-NEW"); input("차단기 종류", "ELB"); input("port 수", "2"); input("정격전류 (A)", "20")
        compose.onNodeWithTag("management-form").performScrollToNode(hasText("설치일 미상"))
        compose.onNode(isToggleable()).performClick()
        input("장소(부하)", "새 부하")
        compose.runOnIdle { model.editForm { it.copy(panelId = 1) }; model.save() }
        assertTrue(model.state.value.form!!.errors.containsKey("panel"))
        compose.runOnIdle { model.chooseFormPanel(2) }
        compose.onNodeWithTag("management-form").performScrollToNode(hasText("저장", substring = false))
        compose.onNodeWithText("저장", substring = false).performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { model.state.value.breakers.size == 1 && model.state.value.form == null } }
        assertEquals(2L, model.state.value.breakers.single().panelId)
        assertEquals(0L, model.state.value.counts.single().tripCount)
        compose.runOnIdle { model.editBreaker(model.state.value.breakers.single()) }
        assertEquals(2, model.state.value.form!!.building)
        assertEquals("3", model.state.value.form!!.floor)
    }

    @Test fun panelFilterIsScopedAndSearchClosesWithCompactResultsAndFullDetails() {
        runBlocking {
            for ((building, floor, number) in listOf(Triple(1,"2","LP-A"), Triple(1,"3","LP-B"), Triple(2,"2","LP-C"))) {
                val p = repo.savePanel(PanelMaster(building = building, floor = floor, number = number, location = "복도"))
                repo.saveBreaker(BreakerMaster(panelId = p, number = "R1", kind = "ELB", ports = 2, ratedAmps = "20", load = "사무실"))
            }
        }
        model = ManagementViewModel(repo, repo, clock)
        compose.setContent { ManagementScreen(model) }
        compose.waitUntil(5_000) { compose.runOnIdle { model.state.value.breakers.size == 3 } }
        compose.onNodeWithText("차단기", substring = false).performClick()
        compose.onNodeWithText("검색·필터").performClick()
        compose.onNodeWithText("관: 전체").performClick(); compose.onNodeWithText("1관", substring = false).performClick()
        compose.onNodeWithText("층: 전체").performClick()
        compose.onNodeWithTag("choices").performScrollToNode(hasText("2")); compose.onNodeWithText("2", substring = false).performClick()
        compose.onNodeWithTag("management-list").performScrollToNode(hasTestTag("filter-panel"))
        compose.onNodeWithTag("filter-panel").performClick()
        compose.onNodeWithText("1관 3층 · LP-B").assertDoesNotExist()
        compose.onNodeWithText("2관 2층 · LP-C").assertDoesNotExist()
        compose.onNodeWithText("1관 2층 · LP-A").performClick()
        compose.onNodeWithTag("management-list").performScrollToNode(hasText("조회", substring = false))
        compose.onNodeWithText("조회", substring = false).performClick()
        compose.onNodeWithText("조회 조건").assertDoesNotExist()
        compose.onNodeWithTag("filter-summary").assertTextEquals("1관 · 2층 · LP-A")
        assertEquals(1, model.state.value.visibleBreakers.size)
        compose.onNodeWithTag("breaker-context-1").assertDoesNotExist()
        compose.onNodeWithTag("breaker-menu-1").performClick(); compose.onNodeWithText("상세 보기").performClick()
        compose.onNodeWithText("1관 2층 · LP-A").assertIsDisplayed()
        compose.onNodeWithText("닫기", substring = false).performClick()
        compose.onNodeWithText("검색·필터").performClick()
        compose.onNodeWithText("관: 1관").performClick(); compose.onNodeWithText("2관", substring = false).performClick()
        assertNull(model.state.value.draft.panelId)
        assertNull(model.state.value.draft.floor)
        assertEquals(1, model.state.value.visibleBreakers.size)
        compose.onNodeWithTag("management-list").performScrollToNode(hasText("초기화", substring = false))
        compose.onNodeWithText("초기화", substring = false).performClick()
        assertEquals(3, model.state.value.visibleBreakers.size)
    }

    @Test fun countsRequireBuildingAndFloorAndHeaderOpensOnlySelectedInstallation() {
        runBlocking {
            val p = repo.savePanel(PanelMaster(building = 1, floor = "3", number = "LP", location = "복도"))
            val b = repo.saveBreaker(BreakerMaster(panelId = p, number = "R1", kind = "MCCB", ports = 2, ratedAmps = "20", load = "사무실"))
            val old = repo.observeCounts().first().single()
            repo.registerTrip(TripRegistration(old.breaker, LocalDate.of(2026, 9, 20), "이전 장소", periodId = old.periodId, requestId = "old", reason = "이전 사유"))
            repo.replaceBreaker(b, LocalDate.of(2026, 9, 25))
            val current = repo.observeCounts().first().single()
            repo.registerTrip(TripRegistration(current.breaker, LocalDate.of(2026, 9, 27), "현재 장소", periodId = current.periodId, requestId = "current", reason = "현재 사유"))
        }
        model = ManagementViewModel(repo, repo, clock)
        compose.setContent { ManagementScreen(model) }
        compose.onNodeWithText("건수조회", substring = false).performClick()
        compose.onNodeWithText("관과 층을 선택한 후 조회하세요.").assertIsDisplayed()
        compose.runOnIdle { model.search() }
        assertTrue(model.state.value.filtersOpen)
        assertFalse(model.state.value.countsReady)
        assertEquals("관과 층을 선택한 후 조회하세요.", model.state.value.filterError)
        compose.onNodeWithTag("management-list").performScrollToNode(hasText("관: 선택"))
        compose.onNodeWithText("관: 선택").assertIsDisplayed().performClick()
        compose.onNodeWithText("1관").performClick()
        compose.onNodeWithText("층: 선택").performClick()
        compose.onNodeWithTag("choices").performScrollToNode(hasText("3")); compose.onNodeWithText("3", substring = false).performClick()
        compose.onNodeWithTag("management-list").performScrollToNode(hasText("조회", substring = false))
        compose.onNodeWithText("조회", substring = false).performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { model.state.value.visibleCounts.size == 1 } }
        assertFalse(model.state.value.filtersOpen)
        compose.onNodeWithText("조회 조건").assertDoesNotExist()
        compose.onNodeWithTag("management-list").performScrollToNode(hasTestTag("count-2"))
        compose.onNodeWithTag("count-2").performClick()
        compose.onNodeWithTag("period-header").performClick()
        compose.onNodeWithText("현재 사유", substring = true).assertIsDisplayed()
        compose.onNodeWithText("이전 사유", substring = true).assertDoesNotExist()
    }

    @Test fun suggestionsSurviveNewViewModelAndTripFormKeepsNotesSeparateFromReason() {
        runBlocking {
            val p = repo.savePanel(PanelMaster(building = 1, floor = "3", number = "LP", location = "복도"))
            repo.saveBreaker(BreakerMaster(panelId = p, number = "R1", kind = "MCCB", ports = 2, ratedAmps = "20.5", load = "사무실"))
        }
        model = ManagementViewModel(repo, repo, clock)
        compose.setContent { ManagementScreen(model) }
        compose.waitUntil(5_000) { compose.runOnIdle { model.state.value.breakers.isNotEmpty() } }
        assertEquals(listOf("MCCB"), model.state.value.suggestions.kinds)
        compose.onNodeWithText("트립이력", substring = false).performClick()
        compose.onNodeWithText("새 트립", substring = false).performClick()
        compose.onNodeWithText("차단기: 선택").performClick()
        compose.onNodeWithText("1관 3층 · LP / R1").performClick()
        fun input(label: String, value: String) {
            compose.onNodeWithTag("management-form").performScrollToNode(hasText(label))
            compose.onNodeWithText(label).performTextInput(value)
        }
        input("트립장소", "사무실")
        input("트립사유", "과부하")
        input("특기사항 (선택)", "확인 필요")
        compose.onNodeWithTag("management-form").performScrollToNode(hasText("저장", substring = false))
        compose.onNodeWithText("저장", substring = false).performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { model.state.value.trips.size == 1 } }
        assertEquals("과부하", model.state.value.trips.single().reason)
        assertEquals("확인 필요", model.state.value.trips.single().note)
        assertEquals(listOf("과부하"), model.state.value.suggestions.reasons)
    }

    @Test fun createsPanelThenBreakerWithoutTripAndReusesSavedKindFromList() {
        model = ManagementViewModel(repo, repo, clock)
        compose.setContent { ManagementScreen(model) }
        compose.onNodeWithText("새 분전함").performClick()
        compose.onNodeWithText("관: 선택").performClick(); compose.onNodeWithText("1관").performClick()
        compose.onNodeWithText("층: 선택").performClick()
        compose.onNodeWithTag("choices").performScrollToNode(hasText("3")); compose.onNodeWithText("3", substring = false).performClick()
        fun input(label: String, value: String) {
            compose.onNodeWithTag("management-form").performScrollToNode(hasText(label))
            compose.onNodeWithText(label).performTextInput(value)
        }
        fun save() {
            compose.onNodeWithTag("management-form").performScrollToNode(hasText("저장", substring = false))
            compose.onNodeWithText("저장", substring = false).performClick()
        }
        input("분전함번호", "lp"); input("분전함위치", "복도"); save()
        compose.waitUntil(5_000) { compose.runOnIdle { model.state.value.panels.size == 1 && model.state.value.form == null } }
        compose.onNodeWithText("차단기", substring = false).performClick()
        compose.onNodeWithText("새 차단기").performClick()
        compose.onNodeWithText("관: 선택").performClick(); compose.onNodeWithText("1관", substring = false).performClick()
        compose.onNodeWithText("층: 선택").performClick()
        compose.onNodeWithTag("choices").performScrollToNode(hasText("3")); compose.onNodeWithText("3", substring = false).performClick()
        compose.onNodeWithText("분전함: 선택").performClick(); compose.onNodeWithText("1관 3층 · LP").performClick()
        input("차단기번호", "r1"); input("차단기 종류", "MCCB"); input("port 수", "2"); input("정격전류 (A)", "30.5")
        compose.onNodeWithTag("management-form").performScrollToNode(hasText("설치일 미상"))
        compose.onNode(isToggleable()).performClick()
        input("장소(부하)", "사무실"); save()
        compose.waitUntil(5_000) { compose.runOnIdle { model.state.value.breakers.size == 1 && model.state.value.form == null } }
        assertEquals(0L, model.state.value.counts.single().tripCount)
        compose.onNodeWithText("새 차단기").performClick()
        compose.onNodeWithTag("management-form").performScrollToNode(hasText("차단기 종류"))
        compose.onNodeWithContentDescription("차단기 종류 저장된 값 선택").performClick()
        compose.onNodeWithText("MCCB", substring = false).performClick()
        assertEquals("MCCB", model.state.value.form?.breakerKind)
    }

    @Test fun editsOneTripAndDeletionRequiresConfirmationWithoutDeletingItsMaster() {
        runBlocking {
            val p = repo.savePanel(PanelMaster(building = 1, floor = "3", number = "LP", location = "복도"))
            repo.saveBreaker(BreakerMaster(panelId = p, number = "R1", kind = "MCCB", ports = 2, ratedAmps = "20", load = "사무실"))
            val c = repo.observeCounts().first().single()
            val t = TripRegistration(c.breaker, LocalDate.of(2026, 9, 27), "현장", periodId = c.periodId, reason = "과부하", requestId = "one")
            repo.saveMasterTrip(null, t); repo.saveMasterTrip(null, t.copy(requestId = "two"))
        }
        model = ManagementViewModel(repo, repo, clock)
        compose.setContent { ManagementScreen(model) }
        compose.waitUntil(5_000) { compose.runOnIdle { model.state.value.trips.size == 2 } }
        compose.onNodeWithText("트립이력", substring = false).performClick()
        compose.onNodeWithTag("management-list").performScrollToNode(hasTestTag("master-trip-1"))
        compose.onNodeWithTag("master-trip-1").performClick()
        compose.onNodeWithText("수정", substring = false).performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { !model.state.value.resolving } }
        compose.onNodeWithTag("management-form").performScrollToNode(hasText("트립사유"))
        compose.onNodeWithText("트립사유", substring = false).performTextReplacement("누전")
        compose.onNodeWithTag("management-form").performScrollToNode(hasText("저장", substring = false))
        compose.onNodeWithText("저장", substring = false).performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { model.state.value.trips.first { it.id == 1L }.reason == "누전" && model.state.value.form == null } }
        assertEquals("과부하", model.state.value.trips.first { it.id == 2L }.reason)
        compose.onNodeWithTag("management-list").performScrollToNode(hasTestTag("master-trip-1"))
        compose.onNodeWithTag("master-trip-1").performClick()
        compose.onNodeWithText("삭제 요청", substring = false).performClick()
        compose.onNodeWithText("삭제 확인", substring = false).assertIsDisplayed()
        compose.onNodeWithText("취소", substring = false).performClick()
        assertEquals(2, model.state.value.trips.size)
        compose.onNodeWithTag("management-list").performScrollToNode(hasTestTag("master-trip-1"))
        compose.onNodeWithTag("master-trip-1").performClick()
        compose.onNodeWithText("삭제 요청", substring = false).performClick()
        compose.onNodeWithText("삭제", substring = false).performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { model.state.value.trips.size == 1 } }
        assertEquals(2L, model.state.value.trips.single().id)
        assertEquals(1, model.state.value.breakers.size)
    }
}
