package com.example.musicfy.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AppSwitchTapTest {
    @Test
    fun displayOnlySwitchTapTogglesParent() = runComposeUiTest {
        var on by mutableStateOf(false)
        setContent {
            Box(Modifier.toggleable(value = on, onValueChange = { on = it })) {
                AppSwitch(checked = on, onCheckedChange = null, modifier = Modifier.testTag("sw"))
            }
        }
        onNodeWithTag("sw", useUnmergedTree = true).performClick()
        waitForIdle()
        assertTrue(on)
    }

    @Test
    fun callbackFiresOnceWithInverse() = runComposeUiTest {
        val calls = mutableListOf<Boolean>()
        setContent {
            AppSwitch(checked = false, onCheckedChange = { calls += it }, modifier = Modifier.testTag("sw"))
        }
        onNodeWithTag("sw").performClick()
        waitForIdle()
        assertEquals(listOf(true), calls)
    }

    @Test
    fun disabledSwitchDoesNotFire() = runComposeUiTest {
        val calls = mutableListOf<Boolean>()
        setContent {
            AppSwitch(checked = false, onCheckedChange = { calls += it }, enabled = false, modifier = Modifier.testTag("sw"))
        }
        onNodeWithTag("sw").performClick()
        waitForIdle()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun rowWithDisplayOnlySwitchTogglesExactlyOnce() = runComposeUiTest {
        var count by mutableIntStateOf(0)
        setContent {
            var on by remember { mutableStateOf(false) }
            Row(Modifier.toggleable(value = on, onValueChange = { on = it; count++ }).padding(4.dp)) {
                AppSwitch(checked = on, onCheckedChange = null, modifier = Modifier.testTag("sw"))
            }
        }
        onNodeWithTag("sw", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(1, count)
    }
}
