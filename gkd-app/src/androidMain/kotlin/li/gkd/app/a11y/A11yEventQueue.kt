package li.gkd.app.a11y

/** Preserve event order when collapsing consecutive events from the same source kind. */
class A11yEventQueue {
    private val events = ArrayDeque<A11yEvent>()

    @Synchronized
    fun add(event: A11yEvent) {
        events.addLast(event)
    }

    @Synchronized
    fun takeBatch(): List<A11yEvent> {
        val head = events.firstOrNull() ?: return emptyList()
        return buildList {
            while (events.firstOrNull()?.sameAs(head) == true) {
                add(events.removeFirst())
            }
        }
    }
}
