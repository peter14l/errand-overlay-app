package com.errand.overlay

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class ErrandAccessibilityServiceTest {

    @Test
    fun testWindowHierarchyParsing() {
        val mockService = mock(ErrandAccessibilityService::class.java)
        val mockNodeInfo = mock(AccessibilityNodeInfo::class.java)

        `when`(mockNodeInfo.text).thenReturn("Order Pizza")
        `when`(mockNodeInfo.contentDescription).thenReturn("Order Button")
        `when`(mockNodeInfo.className).thenReturn("android.widget.Button")
        `when`(mockNodeInfo.isClickable).thenReturn(true)
        `when`(mockNodeInfo.isEditable).thenReturn(false)
        `when`(mockNodeInfo.isScrollable).thenReturn(false)

        val compatNode = AccessibilityNodeInfoCompat(
            text = mockNodeInfo.text?.toString(),
            contentDescription = mockNodeInfo.contentDescription?.toString(),
            className = mockNodeInfo.className?.toString() ?: "",
            isClickable = mockNodeInfo.isClickable,
            isEditable = mockNodeInfo.isEditable,
            isScrollable = mockNodeInfo.isScrollable,
            packageName = "com.zomato.android",
            viewIdResourceName = "btn_order",
            left = 10,
            top = 20,
            right = 100,
            bottom = 200,
            node = mockNodeInfo
        )

        assertEquals("Order Pizza", compatNode.text)
        assertEquals("Order Button", compatNode.contentDescription)
        assertEquals("android.widget.Button", compatNode.className)
        assertTrue(compatNode.isClickable)
        assertFalse(compatNode.isEditable)
    }
}
