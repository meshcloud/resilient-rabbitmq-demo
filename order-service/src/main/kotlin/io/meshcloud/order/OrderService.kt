package io.meshcloud.order

import tools.jackson.databind.ObjectMapper
import io.meshcloud.outbox.OutboundEvent
import io.meshcloud.outbox.OutboundEventRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Creates an [Order] and stages an [OutboundEvent] in a **single atomic transaction**.
 *
 * If the database commit fails, neither row is persisted — no phantom events.
 * If the application crashes after commit, the scheduler will re-publish the event.
 */
@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val outboundEventRepository: OutboundEventRepository,
    private val objectMapper: ObjectMapper
) {

    @Transactional
    fun createOrder(customerId: String, product: String): Order {
        val order = orderRepository.save(Order(customerId = customerId, product = product))

        val payload = objectMapper.writeValueAsString(
            mapOf(
                "orderId" to order.orderId,
                "customerId" to order.customerId,
                "product" to order.product,
                "createdAt" to order.createdAt.toString()
            )
        )

        outboundEventRepository.save(
            OutboundEvent(type = "ORDER_CREATED", payload = payload)
        )

        return order
    }
}
