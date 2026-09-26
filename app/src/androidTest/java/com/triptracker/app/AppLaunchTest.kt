package com.triptracker.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class AppLaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun launchesIntoComposeContent() {
        compose.onNodeWithText(compose.activity.getString(R.string.app_name)).assertIsDisplayed()
        compose.onNodeWithText("트립 등록").assertIsDisplayed()
        compose.onNodeWithText("상세 조회").assertIsDisplayed()
        compose.onNodeWithText("횟수 조회").assertIsDisplayed()
    }
}
