package com.triptracker.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class AppLaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun panelChildFormAndScopedSearchWorkOnDevice() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("A-LE-B2", substring = false).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("A-LE-B2", substring = false).performClick()
        compose.onNodeWithText("관: 1관").assertIsDisplayed()
        compose.onNodeWithText("층: B2").assertIsDisplayed()
        compose.onNodeWithText("분전함: 1관 B2층 · A-LE-B2").assertIsDisplayed()
        compose.onNodeWithText("목록으로").performClick()
        compose.onNodeWithText("차단기", substring = false).performClick()
        compose.onNodeWithText("검색·필터").performClick()
        compose.onNodeWithText("관: 전체").performClick(); compose.onNodeWithText("1관", substring = false).performClick()
        compose.onNodeWithText("층: 전체").performClick(); compose.onNodeWithText("B2", substring = false).performClick()
        compose.onNodeWithTag("management-list").performScrollToNode(hasTestTag("filter-panel"))
        compose.onNodeWithTag("filter-panel").performClick()
        compose.onNodeWithText("1관 B2층 · A-LE-B2").performClick()
        compose.onNodeWithTag("management-list").performScrollToNode(hasText("조회", substring = false))
        compose.onNodeWithText("조회", substring = false).performClick()
        compose.onNodeWithText("조회 조건").assertDoesNotExist()
        compose.onNodeWithTag("filter-summary").assertTextEquals("1관 · B2층 · A-LE-B2")
        compose.onNodeWithText("E1", substring = false).assertIsDisplayed()
    }

    @Test fun launchesIntoComposeContent() {
        compose.onNodeWithText(compose.activity.getString(R.string.app_name)).assertIsDisplayed()
        compose.onNodeWithText("분전함 마스터").assertIsDisplayed()
        compose.onNodeWithText("새 분전함").assertIsDisplayed()
        compose.onNodeWithText("트립이력").assertIsDisplayed()
        compose.onNodeWithText("건수조회").assertIsDisplayed()
        compose.onNodeWithText("위치조회").assertIsDisplayed()
    }
}
