package com.offgrid.android

import com.offgrid.shared.ai.VisibleTextStream
import org.junit.Assert.*
import org.junit.Test

class VisibleTextStreamTest {
    @Test fun hidesReasoningEvenWhenTagsSplitAcrossEveryToken() {
        val parser = VisibleTextStream()
        val raw = "<think>hidden plan</think>Answer [1].<|im_end|>leaked"
        val result = raw.map { parser.append(it.toString()) }.joinToString("") + parser.finish()
        assertEquals("Answer [1].", result)
        assertTrue(parser.stopped)
    }
    @Test fun preservesCodeAndLists() {
        val parser = VisibleTextStream()
        val text = "# Heading\n- Step 1: go\n```kotlin\nval a = 1 < 2\n```"
        assertEquals(text, parser.append(text) + parser.finish())
    }
    @Test fun unfinishedThoughtsAreNotDisplayed() {
        val parser = VisibleTextStream()
        assertEquals("", parser.append("<think>unfinished"))
        assertEquals("", parser.finish())
    }
}
