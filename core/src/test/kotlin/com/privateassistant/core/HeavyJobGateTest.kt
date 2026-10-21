package com.privateassistant.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HeavyJobGateTest {
    @Test
    fun `heavy jobs share a single slot and stale release cannot free a new job`() {
        val gate = HeavyJobGate()
        val first = requireNotNull(gate.tryAcquire())
        assertNull(gate.tryAcquire())
        first.close()
        val second = requireNotNull(gate.tryAcquire())
        first.close()
        assertNull(gate.tryAcquire())
        second.close()
        assertNotNull(gate.tryAcquire())
    }
}
