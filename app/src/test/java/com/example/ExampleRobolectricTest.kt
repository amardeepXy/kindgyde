package com.example

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.example.service.ScreenGuideAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("KindGuide", appName)
  }

  @Test
  fun `gemini live manager updates selected voice`() {
    com.example.ai.GeminiLiveManager.updateSelectedVoice("KORE")
    assertEquals("KORE", com.example.ai.GeminiLiveManager.state.value.selectedVoice)
    com.example.ai.GeminiLiveManager.updateSelectedVoice("AOEDE")
  }

  @Test
  fun `accessibility service inspects clickable nodes and skips passwords`() {
    val clickableNode = AccessibilityNodeInfo().apply {
      text = "Send Photo"
      isClickable = true
      setBoundsInScreen(Rect(20, 40, 220, 120))
    }
    val passwordNode = AccessibilityNodeInfo().apply {
      text = "Secret1234"
      isPassword = true
      isClickable = true
      setBoundsInScreen(Rect(20, 140, 220, 200))
    }

    val labels = mutableListOf<String>()
    val bounds = linkedMapOf<String, Rect>()

    ScreenGuideAccessibilityService.collectInteractiveNodes(clickableNode, labels, bounds)
    ScreenGuideAccessibilityService.collectInteractiveNodes(passwordNode, labels, bounds)

    assertTrue(labels.contains("Send Photo"))
    assertEquals(Rect(20, 40, 220, 120), bounds["Send Photo"])
    assertFalse(labels.contains("Secret1234"))
    assertFalse(bounds.containsKey("Secret1234"))
  }

  @Test
  fun `accessibility service enabled check defaults to false when not in secure settings`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertFalse(ScreenGuideAccessibilityService.isEnabled(context))
  }
}
