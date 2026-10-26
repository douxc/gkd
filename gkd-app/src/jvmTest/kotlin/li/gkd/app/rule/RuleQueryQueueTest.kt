package li.gkd.app.rule

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import li.gkd.app.model.AppInfo
import li.gkd.app.subscription.RawSubscription
import li.gkd.app.subscription.UsedSubsEntry
import li.gkd.db.SubsItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleQueryQueueTest {
    @Test
    fun eventBurstProducesOnePendingQueryAndOrdinaryQueryRemovesForcedRestriction() = runTest {
        val queue = RuleQueryQueue()
        queue.submit(RuleQueryRequest(byForced = true))
        repeat(1000) { queue.submit(RuleQueryRequest(byEvent = true)) }
        val request = queue.next()
        assertTrue(request.byEvent)
        assertFalse(request.byForced)
        val next = async { queue.next() }
        runCurrent()
        assertFalse(next.isCompleted)
        next.cancel()
    }

    @Test
    fun forcedOnlyRequestsKeepTheirRestriction() = runTest {
        val queue = RuleQueryQueue()
        repeat(10) { queue.submit(RuleQueryRequest(byForced = true)) }
        assertTrue(queue.next().byForced)
    }

    @Test
    fun eventArrivingAfterAQueryWasTakenRemainsPending() = runTest {
        val queue = RuleQueryQueue()
        queue.submit(RuleQueryRequest())
        assertFalse(queue.next().byEvent)
        queue.submit(RuleQueryRequest(byEvent = true))
        assertTrue(queue.next().byEvent)
    }

    @Test
    fun ruleSpecificDelaysSurviveOrdinaryQueryCoalescing() = runTest {
        val sub = RawSubscription.parse(
            """{id:1,name:'Test',version:1,apps:[{id:'app.test',groups:[
            {key:1,name:'Test',rules:[{matches:'*'},{matches:'*'}]}]}]}""",
        )
        val rules = RuleSummaryBuilder.build(
            RuleRuntime(), listOf(UsedSubsEntry(SubsItem(1, enable = true, order = 0), sub)),
            mapOf("app.test" to AppInfo("app.test", "Test", 1, "1", false, 0, false, 0)),
            emptyList(), emptyList(), emptyList(),
        ).appIdToRules.getValue("app.test")
        val queue = RuleQueryQueue()
        queue.submit(RuleQueryRequest(delayRule = rules[0]))
        queue.submit(RuleQueryRequest(byEvent = true))
        queue.submit(RuleQueryRequest(delayRule = rules[1]))
        queue.submit(RuleQueryRequest(delayRule = rules[0]))
        assertSame(rules[0], queue.next().delayRule)
        assertEquals(RuleQueryRequest(byEvent = true), queue.next())
        assertSame(rules[1], queue.next().delayRule)
    }
}
