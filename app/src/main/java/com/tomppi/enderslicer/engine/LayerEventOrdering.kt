package com.tomppi.enderslicer.engine

/** Stable layer/source ordering that never uses lexical event IDs as chronology. */
internal object LayerEventOrdering {
    fun normalize(events: List<LayerEvent>): List<LayerEvent> {
        val seenIds = hashSetOf<String>()
        val unique = events.withIndex().filter { seenIds.add(it.value.id) }
        return unique
            .groupBy { EventGroup(it.value.layerNumber, it.value.source) }
            .toSortedMap(compareBy<EventGroup>({ it.layerNumber }, { it.source.ordinal }))
            .values
            .flatMap(::orderWithinGroup)
            .map(IndexedValue<LayerEvent>::value)
    }

    private fun orderWithinGroup(
        events: List<IndexedValue<LayerEvent>>,
    ): List<IndexedValue<LayerEvent>> {
        if (events.firstOrNull()?.value?.source != LayerEventSource.USER) return events
        val ordinals = events.map { indexed -> userOrdinal(indexed.value.id) }
        // Legacy versions lexically sorted persisted user IDs. Reconstruct their
        // numeric creation order only when the whole group uses that known ID
        // format; otherwise retain the caller's stable insertion order.
        if (ordinals.any { it == null }) return events
        return events.indices
            .sortedWith(compareBy({ ordinals[it]!! }, { events[it].index }))
            .map(events::get)
    }

    private fun userOrdinal(id: String): Long? = USER_ID.matchEntire(id)
        ?.groupValues
        ?.get(1)
        ?.toLongOrNull()

    private data class EventGroup(
        val layerNumber: Int,
        val source: LayerEventSource,
    )

    private val USER_ID = Regex("user-(\\d+)")
}
