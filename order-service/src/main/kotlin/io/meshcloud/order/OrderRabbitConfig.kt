package io.meshcloud.order

import org.springframework.amqp.core.*
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OrderRabbitConfig {

    @Bean
    fun ordersExchange(): DirectExchange = DirectExchange("orders")

    @Bean
    fun ordersCreatedQueue(): Queue = QueueBuilder
        .durable("orders.created")
        .build()

    @Bean
    fun ordersCreatedBinding(ordersExchange: DirectExchange, ordersCreatedQueue: Queue): Binding =
        BindingBuilder.bind(ordersCreatedQueue).to(ordersExchange).with("orders.created")
}
