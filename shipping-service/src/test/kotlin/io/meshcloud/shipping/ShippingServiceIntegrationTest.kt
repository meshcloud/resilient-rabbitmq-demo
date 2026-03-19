package io.meshcloud.shipping

import io.meshcloud.retry.RetryQueueInterceptor
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.core.Queue
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestClient
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.rabbitmq.RabbitMQContainer
import java.time.Duration
import java.util.*

/**
 * Integration tests for the Shipping Service.
 *
 * The listener does NOT do manual ack/nack — that is handled by [RetryQueueInterceptor].
 * On failure the listener simply throws an exception; the interceptor catches it,
 * sends the message to the retry queue, and rejects the original delivery.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ShippingServiceIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val rabbitmq: RabbitMQContainer = RabbitMQContainer("rabbitmq:3-management-alpine")
    }

    @TestConfiguration
    class TestQueuesConfig {
        @Bean
        fun ordersCreatedQueue(): Queue = Queue("orders.created", true)
    }

    @LocalServerPort
    private var port: Int = 0

    private val restClient: RestClient get() = RestClient.create("http://localhost:$port")

    @Autowired
    lateinit var orderCreatedListener: OrderCreatedListener

    @Autowired
    lateinit var demoController: DemoController

    @Autowired
    lateinit var rabbitTemplate: RabbitTemplate

    @BeforeEach
    fun resetState() {
        demoController.failRemainingAttempts.set(0)
    }

    // ── Context load ──────────────────────────────────────────────────────────────

    @Test
    fun `application context loads successfully`() {
        // Passes if the Spring context starts without errors.
    }

    // ── POST /demo/fail-next-message ──────────────────────────────────────────────

    @Test
    fun `POST demo fail-next-message returns 200 with confirmation message`() {
        val response = restClient.post()
            .uri("/demo/fail-next-message")
            .retrieve()
            .toEntity(Map::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.get("message") as? String)
            .isEqualTo("Next 6 processing attempts will fail — spring retry + retry queue ladder activated")
    }

    @Test
    fun `POST demo fail-next-message sets the failNextMessage flag to true`() {
        restClient.post().uri("/demo/fail-next-message").retrieve().toBodilessEntity()

        assertThat(demoController.failRemainingAttempts.get()).isEqualTo(6)
    }

    // ── OrderCreatedListener – direct invocation ──────────────────────────────────
    // The listener does NOT handle ack/nack — it just processes or throws.

    @Test
    fun `onOrderCreated returns normally when processing succeeds`() {
        val payload = orderPayload(UUID.randomUUID().toString(), "alice", "Widget")
        val message = Message(payload.toByteArray(), MessageProperties())

        // Should not throw — the RetryQueueInterceptor will ack on success
        orderCreatedListener.onOrderCreated(message)
    }

    @Test
    fun `onOrderCreated throws exception on simulated failure`() {
        demoController.failRemainingAttempts.set(1) // next attempt will fail

        val payload = orderPayload(UUID.randomUUID().toString(), "bob", "Gadget")
        val message = Message(payload.toByteArray(), MessageProperties())

        // Listener throws; RetryQueueInterceptor will catch, send to retry queue, and reject
        assertThrows<RuntimeException> {
            orderCreatedListener.onOrderCreated(message)
        }

        assertThat(demoController.failRemainingAttempts.get()).isEqualTo(0) // fail flag was consumed
    }

    @Test
    fun `onOrderCreated silently skips duplicate orders`() {
        val orderId = UUID.randomUUID().toString()
        val payload = orderPayload(orderId, "carol", "Donut")

        val message1 = Message(payload.toByteArray(), MessageProperties())
        val message2 = Message(payload.toByteArray(), MessageProperties())

        orderCreatedListener.onOrderCreated(message1)
        orderCreatedListener.onOrderCreated(message2) // duplicate — silently skipped
    }

    // ── Full message flow via RabbitMQ ────────────────────────────────────────────

    @Test
    fun `successful message is processed without ending up on retry queue`() {
        val orderId = "success-${UUID.randomUUID()}"
        val payload = orderPayload(orderId, "eve", "Book")

        rabbitTemplate.convertAndSend("orders.created", payload)

        // Give the listener time to process
        Thread.sleep(2_000)

        // No message should be on the first retry queue
        val retryMsg = rabbitTemplate.receive("retry-1", 500)
        assertThat(retryMsg).isNull()
    }

    @Test
    fun `failed message is routed to retry queue by the RetryQueueInterceptor`() {
        demoController.failRemainingAttempts.set(3)

        val payload = orderPayload("retry-${UUID.randomUUID()}", "dave", "Thing")
        rabbitTemplate.convertAndSend("orders.created", payload)

        // Wait for the fail flag to be consumed (listener was invoked)
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(demoController.failRemainingAttempts.get()).isEqualTo(0)
        }

        // The message must now be on the first retry queue
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            val retryMsg = rabbitTemplate.receive("retry-1", 500)
            assertThat(retryMsg).isNotNull()
            assertThat(retryMsg!!.messageProperties.headers[RetryQueueInterceptor.HEADER_RETRIED_COUNT]).isEqualTo(1)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private fun orderPayload(orderId: String, customerId: String, product: String) =
        """{"orderId":"$orderId","customerId":"$customerId","product":"$product","createdAt":"2026-01-01T00:00:00Z"}"""
}

