package com.offgrid.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun localFeaturesRemainReachableWithoutModel() {
        compose.onNodeWithText("Tools", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Works offline · calculated by code").assertIsDisplayed()
        compose.onNodeWithText("Expression").performTextInput("(120 + 80) * 15%")
        compose.onAllNodesWithText("Calculate").onLast().performClick()
        compose.onNodeWithText("30").assertIsDisplayed()
        compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Your little\nlibrary.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Assistant preferences").performClick()
        compose.onNodeWithText("Preferences to remember").assertIsDisplayed()
        compose.onNodeWithText("Dark").performClick()
        compose.onNodeWithText("Dark").assertIsSelected()
        compose.onNodeWithText("Light").performClick()
        compose.onNodeWithText("Light").assertIsSelected()
        compose.onNodeWithText("System").performClick()
    }
}
