package io.meshcloud.order

import io.meshcloud.outbox.OutboundEvent
import io.meshcloud.outbox.OutboundEventRepository
import io.meshcloud.outbox.OutboundEventsPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Integration tests for the Order Service.
 *
 * - Uses a real PostgreSQL container (via Testcontainers) so that Flyway migrations
 *   and JPA behave exactly as in production.
 * - [OutboundEventsPublisher] is mocked to prevent the outbox scheduler from
 *   consuming and marking events as sent while assertions are running.
 * - RabbitMQ listener containers are disabled via test/resources/application.yml
 *   so no live broker is required.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrderServiceIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine")
    }

    /**
     * Prevent [io.meshcloud.outbox.OutboundEventsScheduler] from flushing events
     * to RabbitMQ (which would mark them as sent) while tests are asserting on them.
     */
    @MockitoBean
    lateinit var outboundEventsPublisher: OutboundEventsPublisher

    @LocalServerPort
    var port: Int = 0

    private lateinit var restClient: RestClient

    @Autowired
    lateinit var orderRepository: OrderRepository

    @Autowired
    lateinit var outboundEventRepository: OutboundEventRepository

    @Autowired
    lateinit var orderCreatedEventSender: OrderCreatedEventSender

    @BeforeEach
    fun cleanDatabase() {
        restClient = RestClient.create("http://localhost:$port")
        outboundEventRepository.deleteAll()
        orderRepository.deleteAll()
    }

    // ── Context load ──────────────────────────────────────────────────────────────

    @Test
    fun `application context loads successfully`() {
        // Passes if the Spring context starts without errors.
    }

    // ── POST /orders ──────────────────────────────────────────────────────────────

    @Test
    fun `POST orders returns 200 with order details`() {
        val response = restClient.post()
            .uri("/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf<String, Any>("customerId" to "alice", "product" to "Widget"))
            .retrieve()
            .toEntity(Map::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.get("customerId")).isEqualTo("alice")
        assertThat(response.body?.get("product")).isEqualTo("Widget")
        assertThat(response.body?.get("orderId")).isNotNull()
        assertThat(response.body?.get("message")).isNotNull()
    }

    @Test
    fun `POST orders persists the order to the database`() {
        restClient.post()
            .uri("/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf<String, Any>("customerId" to "bob", "product" to "Gadget"))
            .retrieve()
            .toBodilessEntity()

        val orders = orderRepository.findAll()
        assertThat(orders).hasSize(1)
        assertThat(orders[0].customerId).isEqualTo("bob")
        assertThat(orders[0].product).isEqualTo("Gadget")
        assertThat(orders[0].orderId).isNotBlank()
        assertThat(orders[0].createdAt).isNotNull()
    }

    @Test
    fun `POST orders stages an ORDER_CREATED outbound event in the same transaction`() {
        val response = restClient.post()
            .uri("/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf<String, Any>("customerId" to "carol", "product" to "Donut"))
            .retrieve()
            .toEntity(Map::class.java)

        @Suppress("UNCHECKED_CAST")
        val returnedOrderId = (response.body as Map<String, Any>)["orderId"] as String

        val events = outboundEventRepository.findAll()
        assertThat(events).hasSize(1)

        val event = events[0]
        assertThat(event.type).isEqualTo("ORDER_CREATED")
        assertThat(event.sent).isFalse()
        assertThat(event.failedRetries).isEqualTo(0)
        // Payload must reference the same orderId that was returned to the caller
        assertThat(event.payload).contains(returnedOrderId)
        assertThat(event.payload).contains("carol")
        assertThat(event.payload).contains("Donut")
    }

    @Test
    fun `POST orders creates exactly one order and one event per request`() {
        repeat(3) { i ->
            restClient.post()
                .uri("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf<String, Any>("customerId" to "customer-$i", "product" to "Product-$i"))
                .retrieve()
                .toBodilessEntity()
        }

        assertThat(orderRepository.findAll()).hasSize(3)
        assertThat(outboundEventRepository.findAll()).hasSize(3)
    }

    // ── POST /demo/fail-next-publish ──────────────────────────────────────────────

    @Test
    fun `POST demo fail-next-publish returns 200 and confirmation message`() {
        val response = restClient.post()
            .uri("/demo/fail-next-publish")
            .retrieve()
            .toEntity(Map::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.get("message")).isEqualTo("Next publish will fail")
    }

    @Test
    fun `OrderCreatedEventSender throws on sendOut when fail flag is set, then resets the flag`() {
        orderCreatedEventSender.failNextPublish.set(true)

        val event = OutboundEvent(
            type = "ORDER_CREATED",
            payload = """{"orderId":"test-id","customerId":"eve","product":"Thing","createdAt":"2026-01-01T00:00:00Z"}"""
        )

        assertThrows<RuntimeException> {
            orderCreatedEventSender.sendOut(event)
        }

        // Flag must be reset to false after the simulated failure
        assertThat(orderCreatedEventSender.failNextPublish.get()).isFalse()
    }
}
