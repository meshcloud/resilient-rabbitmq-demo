package io.meshcloud.retry

/**
 * Names and TTLs for the five retry queues.
 * Each queue holds messages for its TTL, then dead-letters them to retry-wait-ended.
 * Separate queues per TTL are mandatory to avoid head-of-line blocking (RabbitMQ FIFO).
 */
object RetryQueues {
    const val RETRY_WAIT_ENDED = "retry-wait-ended"

    val LEVELS = listOf(
        // The first queues have low ttls to fit well for demoing the passing through the retry queues.
        // In Production I would set them to 1m, 5m, 30m
        RetryLevel(0, "retry-1", ttlMs = 20_000),  // 20 seconds
        RetryLevel(1, "retry-2", ttlMs = 15_000),  // 15 seconds
        RetryLevel(2, "retry-3", ttlMs = 300_000),  // 5 minutes
        RetryLevel(3, "retry-4", ttlMs = 3_600_000),  // 1 hour
        RetryLevel(4, "retry-5", ttlMs = 28_800_000), // 8 hours
    )

    fun queueForRetryCount(retriedCount: Int): String =
        LEVELS.getOrElse(retriedCount) { LEVELS.last() }.queueName
}

data class RetryLevel(val index: Int, val queueName: String, val ttlMs: Long)
