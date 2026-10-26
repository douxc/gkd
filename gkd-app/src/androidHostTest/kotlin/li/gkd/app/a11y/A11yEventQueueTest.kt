package li.gkd.app.a11y

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class A11yEventQueueTest {
    private fun event(appId: String, time: Long) = A11yEvent(
        CONTENT_CHANGED, time, appId, "View", AccessibilityEvent(),
    )

    @Test
    fun interleavedSourcesAreNeitherDroppedNorConsumedTwice() {
        val queue = A11yEventQueue()
        val events = listOf(event("a", 1), event("b", 2), event("a", 3))
        events.forEach(queue::add)
        assertEquals(listOf(events[0]), queue.takeBatch())
        assertEquals(listOf(events[1]), queue.takeBatch())
        assertEquals(listOf(events[2]), queue.takeBatch())
        assertTrue(queue.takeBatch().isEmpty())
    }

    @Test
    fun onlyConsecutiveMatchingEventsShareABatch() {
        val queue = A11yEventQueue()
        val events = listOf(event("a", 1), event("a", 2), event("b", 3), event("a", 4))
        events.forEach(queue::add)
        assertEquals(events.take(2), queue.takeBatch())
        assertEquals(listOf(events[2]), queue.takeBatch())
        assertEquals(listOf(events[3]), queue.takeBatch())
    }
}
