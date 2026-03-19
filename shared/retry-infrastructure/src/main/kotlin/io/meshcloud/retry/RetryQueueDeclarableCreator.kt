package io.meshcloud.retry

import org.springframework.amqp.core.Declarables
import org.springframework.amqp.core.QueueBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Declares all retry queues and the retry-wait-ended queue.
 *
 * Each retry queue uses a Dead Letter Exchange (DLX) that routes expired messages
 * to `retry-wait-ended`, where [RetryWaitEndedRabbitListener] re-publishes them
 * to their original exchange/routing-key.
 *
 * Queue configuration:
 * - durable: survives broker restart
 * - x-dead-letter-exchange + x-dead-letter-routing-key: where TTL-expired messages go
 * - x-message-ttl: how long a message waits before being dead-lettered
 * - x-single-active-consumer: enforces FIFO delivery order across consumers
 */
@Configuration
class RetryQueueDeclarableCreator {

    @Bean
    fun retryInfrastructureDeclarables(): Declarables {
        val retryWaitEndedQueue = QueueBuilder
            .durable(RetryQueues.RETRY_WAIT_ENDED)
            .build()

        val retryQueues = RetryQueues.LEVELS.map { level ->
            QueueBuilder
                .durable(level.queueName)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", RetryQueues.RETRY_WAIT_ENDED)
                .withArgument("x-message-ttl", level.ttlMs)
                .withArgument("x-single-active-consumer", true)
                .build()
        }

        return Declarables(retryWaitEndedQueue, *retryQueues.toTypedArray())
    }
}
