package com.uma.workbench.sync

sealed interface SyncResult {
    data class Success(val remoteId: String?) : SyncResult
    data class Retryable(val reason: String, val retryAfterMillis: Long?) : SyncResult
    data class PermanentFailure(val reason: String) : SyncResult
}

interface SyncTransport {
    suspend fun send(kind: String, payload: String, idempotencyKey: String): SyncResult
}

object RetryPolicy {
    const val MAX_ATTEMPTS = 8
    const val BASE_DELAY_MILLIS = 2_000L
    const val MAX_DELAY_MILLIS = 120_000L

    fun delayMillis(attempt: Int): Long {
        val exponent = attempt.coerceIn(0, 16)
        return (BASE_DELAY_MILLIS * (1L shl exponent)).coerceAtMost(MAX_DELAY_MILLIS)
    }
}
