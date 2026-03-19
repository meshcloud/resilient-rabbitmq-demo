package io.meshcloud.shipping

import io.meshcloud.retry.RetryQueueDeclarableCreator
import io.meshcloud.retry.RetryQueueInterceptor
import io.meshcloud.retry.RetryWaitEndedRabbitListener
import org.aopalliance.intercept.MethodInterceptor
import org.springframework.amqp.AmqpRejectAndDontRequeueException
import org.springframework.amqp.core.AcknowledgeMode
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.retry.RetryException
import org.springframework.core.retry.RetryListener
import org.springframework.core.retry.RetryPolicy
import org.springframework.core.retry.RetryState
import org.springframework.core.retry.RetryTemplate
import org.springframework.core.retry.Retryable
import java.time.Duration
import java.util.logging.Logger

/**
 * Configures the listener container with Spring Retry and the [RetryQueueInterceptor] as advice.
 *
 * The advice chain is: RetryQueueInterceptor (outer) → Spring Retry (inner) → listener.
 * Spring Retry handles fast in-memory retries (3 attempts with exponential backoff).
 * When Spring Retry is exhausted, it throws [AmqpRejectAndDontRequeueException] and the
 * outer [RetryQueueInterceptor] routes the message to the DLX+TTL retry queue ladder.
 *
 * Container uses MANUAL ack mode so the interceptor controls when to ack/nack.
 */
@Configuration
@Import(RetryQueueDeclarableCreator::class, RetryWaitEndedRabbitListener::class)
class ShippingRabbitConfig {

    companion object {
        private val log = Logger.getLogger(ShippingRabbitConfig::class.java.name)
    }

    @Bean
    fun rabbitListenerContainerFactory(
        connectionFactory: ConnectionFactory,
        retryQueueInterceptor: RetryQueueInterceptor
    ): SimpleRabbitListenerContainerFactory {
        val retryTemplate = RetryTemplate(
            RetryPolicy.builder()
                .maxRetries(2) // 2 retries = 3 total attempts
                .delay(Duration.ofMillis(500))
                .multiplier(2.0)
                .maxDelay(Duration.ofMillis(5_000))
                .build()
        ).apply {
            setRetryListener(object : RetryListener {
                override fun onRetryableExecution(
                    retryPolicy: RetryPolicy,
                    retryable: Retryable<*>,
                    retryState: RetryState
                ) {
                    if (!retryState.isSuccessful) {
                        log.warning(
                            "Spring Retry: attempt #${retryState.retryCount + 1} failed — ${retryState.lastException.javaClass.simpleName}"
                        )
                    }
                }
            })
        }

        val springRetry = MethodInterceptor { invocation ->
            try {
                retryTemplate.execute<Any?> { invocation.proceed() }
            } catch (ex: RetryException) {
                throw AmqpRejectAndDontRequeueException("Spring retry exhausted", ex.cause)
            }
        }

        return SimpleRabbitListenerContainerFactory().apply {
            setConnectionFactory(connectionFactory)
            setAcknowledgeMode(AcknowledgeMode.MANUAL)
            setAdviceChain(retryQueueInterceptor, springRetry)
        }
    }

    @Bean
    fun retryQueueInterceptor(rabbitTemplate: RabbitTemplate): RetryQueueInterceptor {
        return RetryQueueInterceptor(rabbitTemplate)
    }
}
