package com.csh.blogwriter.ui.components

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.csh.blogwriter.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BottomCtaTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun clickInvokesCallbackWhenEnabled() {
        var clicks = 0
        compose.setContent { AppTheme { BottomCta(text = "다음", onClick = { clicks++ }) } }
        compose.onNodeWithText("다음").assertIsEnabled().performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun disabledAndLoadingBlockClicks() {
        var clicks = 0
        compose.setContent { AppTheme { BottomCta(text = "다음", onClick = { clicks++ }, loading = true) } }
        compose.onNodeWithText("다음").assertIsNotEnabled()
        compose.onNodeWithTag("bottom_cta_loading").assertExists()
        assertEquals(0, clicks)
    }
}
