package io.meshcloud.order

import jakarta.persistence.*
import java.time.Instant
import java.util.*

@Entity
@Table(name = "orders")
class Order(
    val customerId: String,
    val product: String,
    val orderId: String = UUID.randomUUID().toString(),
    val createdAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0
)
