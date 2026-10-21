package com.privateassistant.core

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Sensitive operations have no model-action representation. */
sealed interface SensitiveOperation {
    data class Export(val revision: RevisionRef) : SensitiveOperation
    data class DeleteDocument(val documentId: DocumentId) : SensitiveOperation
}

/** Opaque, single-use capability. It can only be obtained from a current explicit user action. */
class UserActionPermit internal constructor(
    internal val issuer: UUID,
    internal val sessionGeneration: Long,
    internal val operation: SensitiveOperation,
    internal val issuedAt: Instant,
    internal val expiresAt: Instant,
    internal val issuedAtNanos: Long,
)

/**
 * Call [confirmCurrentUserAction] only in a direct export/delete confirmation handler.
 * Call [consume] immediately before opening an export sink or deleting stored data.
 * Unlocking does not revive permits that existed before the lock.
 */
class SensitiveActionAuthorizer(
    private val clock: Clock = Clock.systemUTC(),
    private val permitLifetime: Duration = Duration.ofSeconds(30),
    private val monotonicNanos: () -> Long = { System.nanoTime() },
) {
    private val issuer = UUID.randomUUID()
    private var generation = 0L
    private var unlocked = false
    private var currentPermit: UserActionPermit? = null

    init {
        require(!permitLifetime.isNegative && !permitLifetime.isZero && permitLifetime <= Duration.ofMinutes(1))
    }

    @Synchronized
    fun isUnlocked(): Boolean = unlocked

    @Synchronized
    fun unlock() {
        if (!unlocked) {
            generation++
            unlocked = true
            currentPermit = null
        }
    }

    @Synchronized
    fun lock() {
        generation++
        unlocked = false
        currentPermit = null
    }

    /** A new confirmation supersedes any earlier confirmation, including another operation. */
    @Synchronized
    fun confirmCurrentUserAction(operation: SensitiveOperation): UserActionPermit {
        check(unlocked) { "Unlock the app before confirming a sensitive operation" }
        val now = clock.instant()
        return UserActionPermit(
            issuer,
            generation,
            operation,
            now,
            now.plus(permitLifetime),
            monotonicNanos(),
        ).also { currentPermit = it }
    }

    /** Failed attempts also invalidate the pending permit. */
    @Synchronized
    fun consume(permit: UserActionPermit, operation: SensitiveOperation): Boolean {
        val current = currentPermit
        currentPermit = null
        val now = clock.instant()
        val elapsedNanos = monotonicNanos() - permit.issuedAtNanos
        return unlocked && current === permit && permit.issuer == issuer &&
            permit.sessionGeneration == generation && permit.operation == operation &&
            !now.isBefore(permit.issuedAt) && now.isBefore(permit.expiresAt) &&
            elapsedNanos >= 0 && elapsedNanos < permitLifetime.toNanos()
    }
}
