package io.meshcloud.outbox

import jakarta.persistence.*
import java.time.Instant

/**
 * Represents an outgoing event that has been staged for publishing to RabbitMQ.
 *
 * Written in the **same database transaction** as the business entity, ensuring
 * atomicity. The [OutboundEventsScheduler] reads unsent events and publishes them.
 *
 * Once published: [sent] is set to true and the row is eventually cleaned up.
 * On repeated publish failure: [failedRetries] is incremented; after 100 failures
 * the event is considered poisoned and skipped.
 */
@Entity
@Table(name = "outbound_events")
class OutboundEvent(
    val type: String,

    @Column(columnDefinition = "TEXT")
    val payload: String,

    var sent: Boolean = false,

    var failedRetries: Int = 0,

    var lastModifiedOn: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0
)
