package io.meshcloud.outbox

/**
 * Implement this interface for each event type that should be published via the outbox.
 *
 * The [OutboundEventsPublisher] iterates all registered senders and calls the one
 * matching [canHandle] for each pending [OutboundEvent].
 */
interface OutboundEventSender {
    fun canHandle(type: String): Boolean
    fun sendOut(event: OutboundEvent)
}
