package io.meshcloud.outbox

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.logging.Logger

/**
 * Reads pending [OutboundEvent]s from the database and delegates publishing to
 * the appropriate [OutboundEventSender].
 *
 * Uses READ_COMMITTED isolation + `FOR UPDATE SKIP LOCKED` to allow multiple
 * concurrent instances without deadlocks or blocking concurrent inserts.
 */
@Service
class OutboundEventsPublisher(
    private val repository: OutboundEventRepository,
    private val senders: List<OutboundEventSender>
) {

    companion object {
        private val log = Logger.getLogger(OutboundEventsPublisher::class.java.name)
        private const val BATCH_SIZE = 100
        private const val DELETE_CHUNK_SIZE = 10
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun run() {
        val batch = repository.findPendingBatch(BATCH_SIZE)
        if (batch.isEmpty()) return

        log.fine("Publishing ${batch.size} pending outbound events")

        batch.forEach { event ->
            try {
                val sender = senders.firstOrNull { it.canHandle(event.type) }
                if (sender == null) {
                    log.warning("No sender found for event type '${event.type}' — skipping")
                    return@forEach
                }
                sender.sendOut(event)
                event.sent = true
                event.lastModifiedOn = Instant.now()
            } catch (ex: Exception) {
                log.warning("Failed to publish event ${event.id} (${event.type}): ${ex.message}")
                event.failedRetries++
                event.lastModifiedOn = Instant.now() // bump to back of queue
            }
        }

        repository.saveAll(batch)

        // Cleanup: delete sent events in small chunks to avoid table-lock escalation
        val cutoff = Instant.now().minusSeconds(300)
        var deleted: Int
        do {
            deleted = repository.deleteOldSentEvents(cutoff, DELETE_CHUNK_SIZE)
        } while (deleted == DELETE_CHUNK_SIZE)
    }
}
