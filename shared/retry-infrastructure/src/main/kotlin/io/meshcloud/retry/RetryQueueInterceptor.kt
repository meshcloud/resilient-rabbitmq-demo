package io.meshcloud.retry

import com.rabbitmq.client.Channel
import io.meshcloud.retry.RetryQueueInterceptor.Companion.MAX_RETRIES
import org.aopalliance.intercept.MethodInterceptor
import org.aopalliance.intercept.MethodInvocation
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException
import org.springframework.stereotype.Component
import java.util.logging.Logger

/**
 * AOP [MethodInterceptor] that implements the DLX+TTL retry ladder.
 *
 * Must be added as an Advice to the SimpleRabbitListenerContainerFactory.
 * The container must use AcknowledgeMode.MANUAL, because this interceptor
 * takes over ack/nack: it acks on success, and on failure it sends the message
 * to the next retry queue and rejects the original delivery.
 *
 * Listeners should NOT do manual ack/nack — they just process or throw.
 */
@Component
class RetryQueueInterceptor(
    private val rabbitTemplate: RabbitTemplate
) : MethodInterceptor {

    companion object {
        private val log = Logger.getLogger(RetryQueueInterceptor::class.java.name)
        const val HEADER_RETRIED_COUNT = "x-retried-count"
        const val HEADER_ORIGINAL_EXCHANGE = "x-original-exchange"
        const val HEADER_ORIGINAL_ROUTING_KEY = "x-original-routing-key"
        const val MAX_RETRIES = 5
    }

    override fun invoke(invocation: MethodInvocation): Any? {
        val message = extractMessage(invocation)
        val channel = extractChannel(invocation)

        var ret: Any? = null
        try {
            ret = invocation.proceed()
            if (message != null && channel != null) {
                channel.basicAck(message.messageProperties.deliveryTag, false)
            }
        } catch (ex: Throwable) {
            if (message == null || channel == null) throw ex

            val cause = if (ex is ListenerExecutionFailedException) ex.cause ?: ex else ex

            val headers = message.messageProperties.headers
            val retriedCount = (headers[HEADER_RETRIED_COUNT] as? Int) ?: 0
            val retryQueue = RetryQueues.queueForRetryCount(retriedCount)

            log.warning(
                "Message delivery failed (attempt ${retriedCount + 1}/${MAX_RETRIES}), " +
                        "\u001B[38;5;208mrouting to $retryQueue\u001B[0m. Cause: ${cause.message}"
            )

            // Preserve original destination so retry-wait-ended can re-publish correctly
            val props = message.messageProperties
            if (!headers.containsKey(HEADER_ORIGINAL_EXCHANGE)) {
                props.setHeader(HEADER_ORIGINAL_EXCHANGE, props.receivedExchange ?: "")
                props.setHeader(HEADER_ORIGINAL_ROUTING_KEY, props.receivedRoutingKey ?: "")
            }
            props.setHeader(HEADER_RETRIED_COUNT, retriedCount + 1)

            rabbitTemplate.send(retryQueue, message)

            // Reject the original message (don't requeue — we already re-published to retry)
            channel.basicReject(message.messageProperties.deliveryTag, false)
        }

        return ret
    }

    private fun extractMessage(invocation: MethodInvocation): Message? {
        return invocation.arguments.filterIsInstance<Message>().firstOrNull()
    }

    private fun extractChannel(invocation: MethodInvocation): Channel? {
        return invocation.arguments.filterIsInstance<Channel>().firstOrNull()
    }
}
