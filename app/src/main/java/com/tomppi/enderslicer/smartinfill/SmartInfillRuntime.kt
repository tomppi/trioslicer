package com.tomppi.enderslicer.smartinfill

import com.tomppi.enderslicer.model.SlicerSettings
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Immutable package generation captured once at slice entry. Package files are
 * app-private immutable snapshots, so every request can validate, stage and
 * resolve settings from one generation even if the UI later activates or
 * removes another package.
 */
internal data class SmartInfillSliceSnapshot internal constructor(
    val generation: Long,
    val packageValue: SmartInfillPackage,
) {
    val packageId: String get() = packageValue.id

    fun effective(settings: SlicerSettings): SlicerSettings = packageValue.applyTo(settings)

    fun requireMatchesSource(source: File) = packageValue.requireMatchesSource(source)

    fun stageModifiers(destination: File, analyzedSource: File): List<SmartInfillModifier> =
        packageValue.stageModifiers(destination, analyzedSource)
}

/**
 * Process-local selector for the package shown in the UI. Slicing captures one
 * generation with [snapshot] and installs it with [withSnapshot]. Legacy slice
 * helpers that still call [current] therefore observe the same immutable
 * generation throughout the synchronous Cura request instead of racing UI
 * activation/removal.
 */
internal object SmartInfillRuntime {
    private data class State(
        val generation: Long,
        val packageValue: SmartInfillPackage?,
    )

    private data class SliceOverride(val snapshot: SmartInfillSliceSnapshot?)

    private val state = AtomicReference(State(generation = 0L, packageValue = null))
    private val sliceOverride = ThreadLocal<SliceOverride?>()

    fun activate(packageValue: SmartInfillPackage?) {
        while (true) {
            val previous = state.get()
            val next = State(previous.generation + 1L, packageValue)
            if (state.compareAndSet(previous, next)) return
        }
    }

    fun current(): SmartInfillPackage? {
        val override = sliceOverride.get()
        return if (override != null) override.snapshot?.packageValue else state.get().packageValue
    }

    fun snapshot(): SmartInfillSliceSnapshot? {
        val captured = state.get()
        return captured.packageValue?.let { packageValue ->
            SmartInfillSliceSnapshot(captured.generation, packageValue)
        }
    }

    fun <T> withSnapshot(snapshot: SmartInfillSliceSnapshot?, block: () -> T): T {
        val previous = sliceOverride.get()
        sliceOverride.set(SliceOverride(snapshot))
        return try {
            block()
        } finally {
            if (previous == null) sliceOverride.remove() else sliceOverride.set(previous)
        }
    }

    fun isCurrent(snapshot: SmartInfillSliceSnapshot): Boolean {
        val current = state.get()
        return current.generation == snapshot.generation &&
            current.packageValue?.id == snapshot.packageId
    }
}
