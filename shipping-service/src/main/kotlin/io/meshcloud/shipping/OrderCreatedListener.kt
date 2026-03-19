package io.meshcloud.shipping

import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Listens for ORDER_CREATED events on the orders.created queue.
 *
 * This listener does NOT handle ack/nack — that responsibility belongs to the
 * [io.meshcloud.retry.RetryQueueInterceptor] configured on the container factory.
 * On failure it simply throws an exception; the interceptor catches it,
 * sends the message to the retry queue, and rejects the original delivery.
 */
@Component
class OrderCreatedListener(
    private val demoController: DemoController
) {

    companion object {
        private val log = Logger.getLogger(OrderCreatedListener::class.java.name)
    }

    /** In-memory idempotency store (fine for demo; use DB in production). */
    private val processedOrderIds = ConcurrentHashMap.newKeySet<String>()

    @RabbitListener(queues = ["orders.created"])
    fun onOrderCreated(message: Message) {
        val body = String(message.body)
        log.info("Received message: $body")

        // Simulate failure if demo counter is active
        if (demoController.failRemainingAttempts.getAndDecrement() > 0) {
            log.warning("Simulated processing failure (${demoController.failRemainingAttempts.get() + 1} failures remaining) — will retry")
            throw RuntimeException("Simulated processing failure")
        }

        // Parse orderId for idempotency check
        val orderId = extractOrderId(body)

        if (orderId != null && !processedOrderIds.add(orderId)) {
            log.info("Duplicate message for orderId=$orderId — skipping")
            return
        }

        // Simulate processing
        log.info("Processing order: orderId=$orderId")
        Thread.sleep(50) // simulated work

        log.info("Shipping initiated for orderId=$orderId ✅")
    }

    private fun extractOrderId(body: String): String? {
        return try {
            val idx = body.indexOf("\"orderId\"")
            if (idx == -1) return null
            val start = body.indexOf('"', idx + 10) + 1
            val end = body.indexOf('"', start)
            body.substring(start, end)
        } catch (_: Exception) {
            null
        }
    }
}
