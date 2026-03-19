package io.meshcloud.retry

import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Component
import java.util.logging.Logger

/**
 * Listens to the `retry-wait-ended` queue and re-publishes messages to their
 * original exchange + routing key.
 *
 * Messages arrive here after their TTL expires in one of the retry queues.
 * The [RetryQueueInterceptor] stored the original exchange and routing key in
 * message headers so this listener knows where to send them.
 */
@Component
class RetryWaitEndedRabbitListener(
    private val rabbitTemplate: RabbitTemplate
) {

    companion object {
        private val log = Logger.getLogger(RetryWaitEndedRabbitListener::class.java.name)
    }

    @RabbitListener(queues = [RetryQueues.RETRY_WAIT_ENDED])
    fun onMessage(message: Message) {
        val headers = message.messageProperties.headers
        val exchange = (headers[RetryQueueInterceptor.HEADER_ORIGINAL_EXCHANGE] as? String) ?: ""
        val routingKey = (headers[RetryQueueInterceptor.HEADER_ORIGINAL_ROUTING_KEY] as? String) ?: ""

        val retriedCount = (headers[RetryQueueInterceptor.HEADER_RETRIED_COUNT] as? Int) ?: 0
        log.info("Re-publishing message to exchange='$exchange' routingKey='$routingKey' (retry #$retriedCount)")

        rabbitTemplate.send(exchange, routingKey, message)
    }
}
