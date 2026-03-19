package io.meshcloud.order

import io.meshcloud.outbox.OutboundEvent
import io.meshcloud.outbox.OutboundEventRepository
import io.meshcloud.outbox.OutboundEventsPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant

/**
 * Focused integration test for the outbox cleanup query.
 *
 * Verifies that [OutboundEventRepository.deleteOldSentEvents] correctly removes
 * sent events older than the given cutoff using PostgreSQL-compatible SQL
 * (PostgreSQL does NOT support LIMIT in a DELETE statement).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OutboundEventsCleanupIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine")
    }

    /**
     * Prevent the real publisher from interfering with the test by running its
     * scheduled loop in the background.
     */
    @MockitoBean
    lateinit var outboundEventsPublisher: OutboundEventsPublisher

    /**
     * Mock the RabbitMQ sender so no live broker is needed.
     */
    @MockitoBean
    lateinit var orderCreatedEventSender: OrderCreatedEventSender

    @Autowired
    lateinit var outboundEventRepository: OutboundEventRepository

    @Autowired
    lateinit var transactionTemplate: TransactionTemplate

    @BeforeEach
    fun cleanDatabase() {
        outboundEventRepository.deleteAll()
    }

    @Test
    fun `deleteOldSentEvents removes sent events older than the cutoff and keeps recent ones`() {
        // Given: one sent event that is 10 minutes old (should be deleted)
        //        and one recent unsent event (should be kept)
        outboundEventRepository.saveAll(
            listOf(
                OutboundEvent(
                    type = "ORDER_CREATED",
                    payload = """{"orderId":"old-sent-id"}""",
                    sent = true,
                    lastModifiedOn = Instant.now().minusSeconds(600) // 10 min ago
                ),
                OutboundEvent(
                    type = "ORDER_CREATED",
                    payload = """{"orderId":"recent-unsent-id"}""",
                    sent = false,
                    lastModifiedOn = Instant.now()
                )
            )
        )

        // When: deleting sent events older than 5 minutes
        val cutoff = Instant.now().minusSeconds(300)
        val deleted = transactionTemplate.execute {
            outboundEventRepository.deleteOldSentEvents(cutoff, 100)
        }

        // Then: exactly the old sent event is removed; the recent unsent one survives
        assertThat(deleted).isEqualTo(1)
        val remaining = outboundEventRepository.findAll()
        assertThat(remaining).hasSize(1)
        assertThat(remaining.first().payload).contains("recent-unsent-id")
    }

    @Test
    fun `deleteOldSentEvents respects the chunk size limit`() {
        // Given: three sent events all older than the cutoff
        outboundEventRepository.saveAll(
            (1..3).map { i ->
                OutboundEvent(
                    type = "ORDER_CREATED",
                    payload = """{"orderId":"old-$i"}""",
                    sent = true,
                    lastModifiedOn = Instant.now().minusSeconds(600)
                )
            }
        )

        // When: deleting with a chunk size of 2
        val cutoff = Instant.now().minusSeconds(300)
        val deleted = transactionTemplate.execute {
            outboundEventRepository.deleteOldSentEvents(cutoff, 2)
        }

        // Then: at most 2 rows are deleted per call
        assertThat(deleted).isEqualTo(2)
        assertThat(outboundEventRepository.findAll()).hasSize(1)
    }
}


