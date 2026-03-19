package io.meshcloud.order

import tools.jackson.databind.ObjectMapper
import io.meshcloud.outbox.OutboundEvent
import io.meshcloud.outbox.OutboundEventSender
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

@Component
class OrderCreatedEventSender(
    private val rabbitTemplate: RabbitTemplate,
    private val objectMapper: ObjectMapper
) : OutboundEventSender {

    companion object {
        private val log = Logger.getLogger(OrderCreatedEventSender::class.java.name)
        const val EVENT_TYPE = "ORDER_CREATED"
    }

    /** Set to true via demo endpoint to simulate a publish failure. */
    val failNextPublish = AtomicBoolean(false)

    override fun canHandle(type: String) = type == EVENT_TYPE

    override fun sendOut(event: OutboundEvent) {
        if (failNextPublish.compareAndSet(true, false)) {
            throw RuntimeException("Simulated publish failure for demo purposes")
        }

        val payload = objectMapper.readTree(event.payload)
        log.info("Publishing ORDER_CREATED event for orderId=${payload["orderId"]}")

        rabbitTemplate.convertAndSend(
            "orders",          // exchange
            "orders.created",  // routing key
            event.payload
        )
    }
}
