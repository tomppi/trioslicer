package com.tomppi.enderslicer.engine

internal class FloatAccumulator(initialCapacity: Int) {
    private var values = FloatArray(initialCapacity)
    var size: Int = 0
        private set

    fun add(vararg additions: Float) {
        ensure(size + additions.size)
        additions.copyInto(values, destinationOffset = size)
        size += additions.size
    }

    fun toArray(): FloatArray = values.copyOf(size)

    private fun ensure(required: Int) {
        if (required <= values.size) return
        var capacity = values.size
        while (capacity < required) capacity *= 2
        values = values.copyOf(capacity)
    }
}
