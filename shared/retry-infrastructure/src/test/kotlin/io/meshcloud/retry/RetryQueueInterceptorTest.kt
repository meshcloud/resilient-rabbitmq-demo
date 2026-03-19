package io.meshcloud.retry

import com.rabbitmq.client.Channel
import org.aopalliance.intercept.MethodInvocation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.rabbit.core.RabbitTemplate

class RetryQueueInterceptorTest {

    private lateinit var rabbitTemplate: RabbitTemplate
    private lateinit var interceptor: RetryQueueInterceptor
    private lateinit var channel: Channel
    private lateinit var invocation: MethodInvocation

    @BeforeEach
    fun setUp() {
        rabbitTemplate = mock()
        interceptor = RetryQueueInterceptor(rabbitTemplate)
        channel = mock()
        invocation = mock()
    }

    private fun messageWithDeliveryTag(deliveryTag: Long, headers: Map<String, Any> = emptyMap()): Message {
        val props = MessageProperties().apply {
            this.deliveryTag = deliveryTag
            headers.forEach { (k, v) -> setHeader(k, v) }
        }
        return Message("""{"orderId":"test-123"}""".toByteArray(), props)
    }

    private fun setupInvocation(message: Message) {
        whenever(invocation.arguments).thenReturn(arrayOf(channel, message))
    }

    // ── Success path ──────────────────────────────────────────────────────────────

    @Test
    fun `acks message on successful invocation`() {
        val message = messageWithDeliveryTag(42L)
        setupInvocation(message)
        whenever(invocation.proceed()).thenReturn(null)

        interceptor.invoke(invocation)

        verify(channel).basicAck(42L, false)
        verify(channel, never()).basicReject(any(), any())
        verifyNoInteractions(rabbitTemplate)
    }

    // ── Failure path ──────────────────────────────────────────────────────────────

    @Test
    fun `sends message to first retry queue on first failure`() {
        val message = messageWithDeliveryTag(7L)
        setupInvocation(message)
        whenever(invocation.proceed()).thenThrow(RuntimeException("Processing failed"))

        interceptor.invoke(invocation)

        // Should send to retry-1 (first retry queue)
        verify(rabbitTemplate).send(eq("retry-1"), any<Message>())
        // Should reject original message without requeue
        verify(channel).basicReject(7L, false)
        // Should NOT ack
        verify(channel, never()).basicAck(any(), any())
    }

    @Test
    fun `increments retry count header when sending to retry queue`() {
        val message = messageWithDeliveryTag(7L)
        setupInvocation(message)
        whenever(invocation.proceed()).thenThrow(RuntimeException("fail"))

        interceptor.invoke(invocation)

        val messageCaptor = argumentCaptor<Message>()
        verify(rabbitTemplate).send(any(), messageCaptor.capture())

        val sentMessage = messageCaptor.firstValue
        assertThat(sentMessage.messageProperties.headers[RetryQueueInterceptor.HEADER_RETRIED_COUNT]).isEqualTo(1)
    }

    @Test
    fun `preserves original exchange and routing key headers on first failure`() {
        val message = messageWithDeliveryTag(7L)
        setupInvocation(message)
        whenever(invocation.proceed()).thenThrow(RuntimeException("fail"))

        interceptor.invoke(invocation)

        val messageCaptor = argumentCaptor<Message>()
        verify(rabbitTemplate).send(any(), messageCaptor.capture())

        val headers = messageCaptor.firstValue.messageProperties.headers
        // receivedExchange/receivedRoutingKey are null in unit tests (set by broker on delivery)
        // but the interceptor preserves whatever value is there (defaulting to "")
        assertThat(headers).containsKey(RetryQueueInterceptor.HEADER_ORIGINAL_EXCHANGE)
        assertThat(headers).containsKey(RetryQueueInterceptor.HEADER_ORIGINAL_ROUTING_KEY)
    }

    @Test
    fun `routes to second retry queue on second failure`() {
        val message = messageWithDeliveryTag(7L, mapOf(RetryQueueInterceptor.HEADER_RETRIED_COUNT to 1))
        setupInvocation(message)
        whenever(invocation.proceed()).thenThrow(RuntimeException("fail again"))

        interceptor.invoke(invocation)

        verify(rabbitTemplate).send(eq("retry-2"), any<Message>())
        verify(channel).basicReject(7L, false)
    }

    @Test
    fun `routes to last retry queue when max retries exceeded`() {
        val message = messageWithDeliveryTag(7L, mapOf(RetryQueueInterceptor.HEADER_RETRIED_COUNT to 10))
        setupInvocation(message)
        whenever(invocation.proceed()).thenThrow(RuntimeException("still failing"))

        interceptor.invoke(invocation)

        verify(rabbitTemplate).send(eq("retry-5"), any<Message>())
        verify(channel).basicReject(7L, false)
    }
}
