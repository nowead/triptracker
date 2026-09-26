package com.triptracker.app.presentation

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import com.triptracker.app.domain.BreakerKey
import com.triptracker.app.domain.TripRegistration
import com.triptracker.app.data.local.TripDatabase
import com.triptracker.app.data.repository.RoomTripRepository
import java.time.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RegistrationScreenTest {
    @get:Rule val compose = createComposeRule()
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), TripDatabase::class.java).build()
    private val clock = Clock.fixed(Instant.parse("2026-09-15T01:00:00Z"), ZoneId.of("Asia/Seoul"))
    private lateinit var model: TripViewModel
    @After fun close() { if (::model.isInitialized) model.viewModelScope.cancel(); db.close() }

    @Test fun firstUnknownTripCanBeSavedAndCountedFromTheScreen() {
        model = TripViewModel(RoomTripRepository(db, clock), clock)
        compose.setContent { TripTrackerScreen(model) }
        compose.onNodeWithText("트립 등록").performClick()
        compose.onNodeWithText("관: 선택").performClick()
        compose.onNodeWithText("1관", useUnmergedTree = true).performClick()
        compose.onNodeWithText("층: 선택").performClick()
        compose.onNodeWithTag("choices").performScrollToNode(hasText("3"))
        compose.onNodeWithText("3").performClick()
        fun input(label: String, value: String) {
            compose.onNodeWithTag("registration").performScrollToNode(hasText(label))
            compose.onNodeWithText(label).performTextInput(value)
        }
        input("분전함번호", "lp-3-a")
        input("차단기명", "r1")
        input("장소", "사무실")
        compose.waitUntil(5_000) { compose.runOnIdle { model.state.value.resolvedKey != null } }
        compose.onNodeWithTag("registration").performScrollToNode(hasText("교체일 미상"))
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithTag("registration").performScrollToNode(hasText("저장"))
        compose.onNodeWithText("저장", substring = false).performClick()
        try { compose.waitUntil(5_000) { compose.runOnIdle { model.state.value.trips.size == 1 } } }
        catch (failure: Throwable) { throw AssertionError("Save state: ${model.state.value}", failure) }
        compose.onNodeWithTag("results").performScrollToNode(hasText("트립 2026-09-15 · 이력 #1"))
        compose.onNodeWithText("트립 2026-09-15 · 이력 #1").assertIsDisplayed()
        compose.onNodeWithText("횟수 조회").performClick()
        compose.onNodeWithTag("results").performScrollToNode(hasText("1회"))
        compose.onNodeWithText("1회").assertIsDisplayed()
        compose.onNodeWithText("트립 등록").performClick()
        compose.onNodeWithText("관: 선택").performClick()
        compose.onNodeWithText("1관").performClick()
        compose.onNodeWithText("층: 선택").performClick()
        compose.onNodeWithTag("choices").performScrollToNode(hasText("3"))
        compose.onNodeWithText("3").performClick()
        input("분전함번호", "lp-3-a")
        input("차단기명", "r1")
        input("장소", "사무실")
        try { compose.waitUntil(5_000) { compose.runOnIdle { model.state.value.form.periodId != null } } }
        catch (failure: Throwable) { throw AssertionError("Existing breaker state: ${model.state.value}", failure) }
        compose.onNodeWithTag("registration").performScrollToNode(hasText("저장"))
        compose.onNodeWithText("저장").performClick()
        try { compose.waitUntil(5_000) { compose.runOnIdle { model.state.value.trips.size == 2 } } }
        catch (failure: Throwable) { throw AssertionError("Second save state: ${model.state.value}", failure) }
        compose.onNodeWithText("횟수 조회").performClick()
        compose.onNodeWithTag("results").performScrollToNode(hasText("2회"))
        compose.onNodeWithText("2회").assertIsDisplayed()
    }

    @Test fun editsOneTripThenConfirmsSelectedDeletionAndKeepsZeroCountPeriod() {
        val repository = RoomTripRepository(db, clock)
        runBlocking {
            val first = TripRegistration(BreakerKey(1, "3", "LP", "R1"), LocalDate.of(2026, 9, 15), "사무실",
                replacementUnknown = true, requestId = "one")
            repository.registerTrip(first)
            val p = repository.findPeriods(first.breaker).single().id
            repository.registerTrip(first.copy(periodId = p, requestId = "two"))
            repository.registerTrip(first.copy(periodId = p, requestId = "three"))
        }
        model = TripViewModel(repository, clock)
        compose.setContent { TripTrackerScreen(model) }
        compose.waitUntil(5_000) { compose.runOnIdle { model.state.value.trips.size == 3 } }
        compose.onNodeWithText("상세 조회").performClick()
        compose.onNodeWithTag("results").performScrollToNode(hasText("트립 2026-09-15 · 이력 #1"))
        compose.onNodeWithText("트립 2026-09-15 · 이력 #1").performClick()
        compose.onNodeWithText("수정").performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { model.state.value.form.periodId != null } }
        compose.onNodeWithTag("registration").performScrollToNode(hasText("장소"))
        compose.onNodeWithText("장소").performTextReplacement("변경 장소")
        compose.onNodeWithTag("registration").performScrollToNode(hasText("수정 완료"))
        compose.onNodeWithText("수정 완료").performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { model.state.value.trips.any { it.id == 1L && it.location == "변경 장소" } } }
        org.junit.Assert.assertEquals("사무실", model.state.value.trips.first { it.id == 2L }.location)
        fun select(id: Long) {
            compose.onNodeWithTag("results").performScrollToNode(hasTestTag("select-trip-$id"))
            compose.onNodeWithTag("select-trip-$id").performClick()
        }
        compose.onNodeWithText("선택", substring = false).performClick()
        select(1); select(2)
        compose.onNodeWithText("선택 2건 삭제").performClick()
        compose.onNodeWithText("취소").performClick()
        org.junit.Assert.assertEquals(3, model.state.value.trips.size)
        compose.onNodeWithText("선택 2건 삭제").performClick()
        compose.onNodeWithText("삭제", substring = false).performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { model.state.value.trips.map { it.id } == listOf(3L) } }
        select(3)
        compose.onNodeWithText("선택 1건 삭제").performClick()
        compose.onNodeWithText("삭제", substring = false).performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { model.state.value.trips.isEmpty() } }
        compose.onNodeWithText("횟수 조회").performClick()
        compose.onNodeWithTag("results").performScrollToNode(hasText("0회"))
        compose.onNodeWithText("0회").assertIsDisplayed()
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Config(sdk = [28], qualifiers = "w411dp-h891dp-xhdpi")
    @Test fun dashboardExpandsPanelsAndOpensOnlyTheChosenBreakerWithExplicitSelection() {
        val repository = RoomTripRepository(db, clock)
        runBlocking {
            val first = TripRegistration(BreakerKey(1, "3", "LP-3-A", "R1"), LocalDate.of(2026, 9, 15), "사무실",
                replacementUnknown = true, requestId = "home-one")
            repository.registerTrip(first)
            repository.registerTrip(first.copy(periodId = repository.findPeriods(first.breaker).single().id, requestId = "home-two"))
            repository.registerTrip(first.copy(breaker = first.breaker.copy(breakerName = "R10"), requestId = "home-three"))
            repository.registerTrip(first.copy(breaker = first.breaker.copy(building = 2), requestId = "home-four"))
        }
        model = TripViewModel(repository, clock)
        compose.setContent { TripTrackerScreen(model) }
        compose.waitUntil(5_000) { compose.runOnIdle { !model.state.value.loadingHome } }
        compose.onNodeWithText("Trip Tracker").assertIsDisplayed()
        compose.onNodeWithTag("home-total").assertTextEquals("4")
        compose.onNodeWithText("1관", substring = false).performClick()
        compose.onNodeWithTag("home-total").assertTextEquals("3")
        compose.onNodeWithTag("home").performScrollToNode(hasTestTag("panel-1-3-LP-3-A"))
        compose.onNodeWithTag("panel-1-3-LP-3-A").performClick()
        compose.waitForIdle()
        val screenshot = java.io.File("build/reports/ui/home.png")
        screenshot.parentFile.mkdirs()
        compose.runOnIdle {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).single()
            val view = activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            screenshot.outputStream().use { output -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output) }
            bitmap.recycle()
        }
        compose.onNodeWithTag("home").performScrollToNode(hasTestTag("breaker-1"))
        compose.onNodeWithTag("breaker-1").performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { !model.state.value.loadingTrips } }
        org.junit.Assert.assertEquals(listOf(2L, 1L), model.state.value.trips.map { it.id })
        compose.onNodeWithText("이 차단기의 전체 교체 구간 이력").assertIsDisplayed()
        compose.onNodeWithTag("select-trip-1").assertDoesNotExist()
        compose.onNodeWithText("선택", substring = false).performClick()
        compose.onNodeWithTag("results").performScrollToNode(hasTestTag("select-trip-1"))
        compose.onNodeWithTag("select-trip-1").performClick()
        compose.onNodeWithText("선택 1건 삭제").performClick()
        compose.onNodeWithText("삭제", substring = false).performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { model.state.value.homeCounts.sumOf { it.tripCount } == 3L } }
        compose.onNodeWithText("현황", substring = false).performClick()
        compose.onNodeWithTag("home-total").assertTextEquals("2")
        compose.onNodeWithTag("home").performScrollToNode(hasTestTag("breaker-1"))
        compose.onNodeWithTag("breaker-1").assertIsDisplayed()
        compose.onNodeWithTag("panel-1-3-LP-3-A").performClick()
        compose.onNodeWithTag("breaker-1").assertDoesNotExist()
    }
}
