package com.privateassistant.core

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveActionAuthorizerTest {
    private val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val authorizer = SensitiveActionAuthorizer(clock)
    private val ref = RevisionRef(DocumentId("document"), RevisionId("v1"))
    private val export = SensitiveOperation.Export(ref)
    private val delete = SensitiveOperation.DeleteDocument(ref.documentId)

    @Test
    fun `locked app cannot issue an export or deletion permit`() {
        assertFalse(authorizer.isUnlocked())
        assertThrows(IllegalStateException::class.java) { authorizer.confirmCurrentUserAction(export) }
        assertThrows(IllegalStateException::class.java) { authorizer.confirmCurrentUserAction(delete) }
    }

    @Test
    fun `explicit export permit is single use`() {
        authorizer.unlock()
        val permit = authorizer.confirmCurrentUserAction(export)
        assertTrue(authorizer.consume(permit, export))
        assertFalse(authorizer.consume(permit, export))
    }

    @Test
    fun `export permission cannot authorize deletion or a different revision`() {
        authorizer.unlock()
        val permit = authorizer.confirmCurrentUserAction(export)
        assertFalse(authorizer.consume(permit, delete))
        val anotherPermit = authorizer.confirmCurrentUserAction(export)
        assertFalse(authorizer.consume(anotherPermit, SensitiveOperation.Export(ref.copy(revisionId = RevisionId("v2")))))
    }

    @Test
    fun `background lock revokes permission even after app is unlocked again`() {
        authorizer.unlock()
        val permit = authorizer.confirmCurrentUserAction(delete)
        authorizer.lock()
        authorizer.unlock()
        assertFalse(authorizer.consume(permit, delete))
    }

    @Test
    fun `expired confirmation requires a new explicit action`() {
        authorizer.unlock()
        val permit = authorizer.confirmCurrentUserAction(export)
        clock.now = clock.now.plusSeconds(30)
        assertFalse(authorizer.consume(permit, export))
        assertTrue(authorizer.consume(authorizer.confirmCurrentUserAction(export), export))

        val beforeRollback = authorizer.confirmCurrentUserAction(export)
        clock.now = clock.now.minusSeconds(1)
        assertFalse(authorizer.consume(beforeRollback, export))

        var elapsedNanos = 0L
        val monotonicAuthorizer = SensitiveActionAuthorizer(clock, monotonicNanos = { elapsedNanos }).also { it.unlock() }
        val agedPermit = monotonicAuthorizer.confirmCurrentUserAction(export)
        elapsedNanos = Duration.ofSeconds(30).toNanos()
        assertFalse(monotonicAuthorizer.consume(agedPermit, export))
    }

    @Test
    fun `new confirmation supersedes old and permits cannot cross sessions`() {
        authorizer.unlock()
        val old = authorizer.confirmCurrentUserAction(export)
        authorizer.confirmCurrentUserAction(delete)
        assertFalse(authorizer.consume(old, export))

        val other = SensitiveActionAuthorizer(clock).also { it.unlock() }
        val foreign = other.confirmCurrentUserAction(delete)
        assertFalse(authorizer.consume(foreign, delete))
    }

    @Test
    fun `permit lifetime cannot silently become unlimited`() {
        assertThrows(IllegalArgumentException::class.java) { SensitiveActionAuthorizer(clock, Duration.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { SensitiveActionAuthorizer(clock, Duration.ofDays(1)) }
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
