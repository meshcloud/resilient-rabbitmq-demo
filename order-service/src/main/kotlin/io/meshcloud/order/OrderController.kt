package io.meshcloud.order

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class CreateOrderRequest(val customerId: String, val product: String)

@RestController
@RequestMapping
class OrderController(
    private val orderService: OrderService,
    private val orderCreatedEventSender: OrderCreatedEventSender
) {

    /**
     * Create a new order. The event is staged atomically with the order row.
     *
     * ```
     * curl -s -X POST http://localhost:8080/orders \
     *   -H 'Content-Type: application/json' \
     *   -d '{"customerId":"alice","product":"Widget"}'
     * ```
     */
    @PostMapping("/orders")
    fun createOrder(@RequestBody req: CreateOrderRequest): ResponseEntity<Map<String, Any>> {
        val order = orderService.createOrder(req.customerId, req.product)
        return ResponseEntity.ok(
            mapOf(
                "orderId" to order.orderId,
                "customerId" to order.customerId,
                "product" to order.product,
                "message" to "Order created; event staged for publishing"
            )
        )
    }

    /**
     * Toggle: next publish attempt will throw, simulating an unreachable broker.
     *
     * ```
     * curl -s -X POST http://localhost:8080/demo/fail-next-publish
     * ```
     */
    @PostMapping("/demo/fail-next-publish")
    fun failNextPublish(): ResponseEntity<Map<String, String>> {
        orderCreatedEventSender.failNextPublish.set(true)
        return ResponseEntity.ok(mapOf("message" to "Next publish will fail"))
    }
}
