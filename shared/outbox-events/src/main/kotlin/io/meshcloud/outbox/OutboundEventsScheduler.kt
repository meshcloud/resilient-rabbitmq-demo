package io.meshcloud.outbox

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Triggers [OutboundEventsPublisher.run] every second.
 *
 * Enable with `@EnableScheduling` on your Spring Boot application.
 */
@Component
class OutboundEventsScheduler(
    private val publisher: OutboundEventsPublisher
) {

    @Scheduled(fixedDelay = 1000)
    fun publishPendingEvents() {
        publisher.run()
    }
}
