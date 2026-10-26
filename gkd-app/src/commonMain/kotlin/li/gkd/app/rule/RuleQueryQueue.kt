package li.gkd.app.rule

import kotlinx.coroutines.channels.Channel

data class RuleQueryRequest(
    val byEvent: Boolean = false,
    val byForced: Boolean = false,
    val delayRule: ResolvedRule? = null,
)

/** One consumer owns query execution; pending requests retain their rule-specific delays. */
class RuleQueryQueue {
    private val pending = linkedMapOf<ResolvedRule?, RuleQueryRequest>()
    private val wakeup = Channel<Unit>(Channel.CONFLATED)

    fun submit(request: RuleQueryRequest) {
        synchronized(pending) {
            val previous = pending[request.delayRule]
            pending[request.delayRule] = if (previous == null) request else request.copy(
                byEvent = previous.byEvent || request.byEvent,
                // An ordinary query must not inherit the restrictions of a forced-only query.
                byForced = previous.byForced && request.byForced,
            )
        }
        wakeup.trySend(Unit)
    }

    suspend fun next(): RuleQueryRequest {
        while (true) {
            wakeup.receive()
            val request = synchronized(pending) {
                val entry = pending.entries.firstOrNull() ?: return@synchronized null
                val value = pending.remove(entry.key)
                if (pending.isNotEmpty()) wakeup.trySend(Unit)
                value
            }
            if (request != null) return request
        }
    }
}
