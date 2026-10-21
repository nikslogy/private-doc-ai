package com.privateassistant.core

import java.util.concurrent.atomic.AtomicBoolean

/** Shared by OCR, rendering and inference, so they cannot run as separate heavy jobs at once. */
class HeavyJobGate {
    private var active: Lease? = null

    @Synchronized
    fun tryAcquire(): Lease? {
        if (active != null) return null
        return Lease { release(it) }.also { active = it }
    }

    @Synchronized
    private fun release(lease: Lease) {
        if (active === lease) active = null
    }

    class Lease internal constructor(private val release: (Lease) -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) release(this)
        }
    }
}
