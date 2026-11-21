package com.shilapi.xcertplay.media

/** Serializes gain changes with track creation/recovery/removal. No system volume. */
class MediaOutputGain {
    private var gain = 1f
    private val outputs = LinkedHashMap<Any, (Float) -> Unit>()

    @Synchronized fun set(value: Float): Boolean {
        require(value.isFinite() && value in 0f..1f)
        gain = value
        var applied = true
        outputs.values.forEach { apply -> if (runCatching { apply(gain) }.isFailure) applied = false }
        return applied
    }
    @Synchronized fun attach(owner: Any, apply: (Float) -> Unit): Boolean {
        outputs[owner] = apply
        return runCatching { apply(gain) }.isSuccess
    }
    @Synchronized fun refresh(owner: Any): Boolean =
        outputs[owner]?.let { runCatching { it(gain) }.isSuccess } ?: false
    @Synchronized fun detach(owner: Any) { outputs.remove(owner) }
    @Synchronized fun current(): Float = gain
}
