# RabbitMQ Reliable Messaging Demo

A self-contained demo showing two complementary patterns for reliable messaging with RabbitMQ:

| Pattern                  | Location           | Guarantees                                      |
|--------------------------|--------------------|-------------------------------------------------|
| **Transactional Outbox** | `order-service`    | At-least-once publish (no lost events)          |
| **DLX+TTL Retry Ladder** | `shipping-service` | At-least-once consume with exponential back-off |

---

## Architecture

```
                  ┌────────────────────────────────────┐
                  │         order-service :8080        │
                  │                                    │
  POST /orders ──►│  OrderService                      │
                  │   ├─ save Order             ─┐     │
                  │   └─ save OutboundEvent     ─┘ tx  │
                  │                                    │
                  │  OutboundEventsScheduler (1 s)     │
                  │   └─ SELECT FOR UPDATE SKIP LOCKED │
                  │       └─ RabbitTemplate.send ──────┼──► exchange: orders
                  └────────────────────────────────────┘      routing key: orders.created
                                                                      │
                                                              queue: orders.created
                                                                      │
                  ┌────────────────────────────────────┐              │
                  │       shipping-service :8081       │◄─────────────┘
                  │                                    │
                  │  OrderCreatedListener              │
                  │   └─ Spring Retry (3×, 0.5–5 s)    │
                  │       └─ RetryQueueInterceptor     │
                  │           ├─ retry-1  (1 min)      │
                  │           ├─ retry-2  (5 min)      │
                  │           ├─ retry-3  (30 min)     │
                  │           ├─ retry-4  (1 h)        │
                  │           └─ retry-5  (8 h)        │
                  │               └─ retry-wait-ended  │
                  │                   └─ re-publish    │
                  └────────────────────────────────────┘
```

---

## Understanding the concepts

This repository contains a presentation folder that explains the concepts used in this demo application.
The markdown version of the presentation also contains explanatory comments for every slide.

## Quick Start

### Prerequisites

- Docker & Docker Compose
- Java 21+
- (Gradle wrapper included — no local Gradle needed)

### 1. Start infrastructure

```bash
cd rabbitmq-demo
docker-compose up -d
```

Verify:

- RabbitMQ management UI: http://localhost:15672  (guest / guest)
- PostgreSQL: `localhost:5432`, database `orders`, user `orders`, password `orders`

### 2. Start order-service

This service must be started first as this one creates the queues and exchanges on first startup.

```bash
./gradlew :order-service:bootRun
```

### 3. Start shipping-service

```bash
./gradlew :shipping-service:bootRun
```

---

## Demo Scenarios

### Scenario 1 — Happy Path

```bash
curl -s -X POST http://localhost:8080/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"alice","product":"Widget"}' | jq
```

**Expected:**

- `order-service` logs: `Publishing ORDER_CREATED event for orderId=…`
- `shipping-service` logs: `Shipping initiated for orderId=… ✅`

---

### Scenario 2 — Outbox: Publish Failure

Simulate the broker being temporarily unreachable during the next publish attempt.

```bash
# 1. Arm the failure toggle
curl -s -X POST http://localhost:8080/demo/fail-next-publish

# 2. Create an order (event is saved to DB but publish fails)
curl -s -X POST http://localhost:8080/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"bob","product":"Gadget"}'
```

**Expected:**

- `order-service` logs: `Failed to publish event … Simulated publish failure`
- Within ~1 second the scheduler retries: `Publishing ORDER_CREATED event for orderId=…`
- `shipping-service` eventually processes it

---

### Scenario 3 — Retry Ladder: Consumer Failure

Simulate the consumer failing to process a message.

```bash
# 1. Arm the failure toggle on the consumer
curl -s -X POST http://localhost:8081/demo/fail-next-message

# 2. Send an order
curl -s -X POST http://localhost:8080/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"carol","product":"Doohickey"}'
```

**Expected:**

- Spring Retry makes 3 immediate attempts (visible in logs)
- `RetryQueueInterceptor` routes message to `retry-1` (TTL 20s for demo purposes)
- After 20s the message re-appears on `orders.created` and the 3 Spring retries fail again
- routes to `retry-2` (TTL: 15s)
- After 15s the message re-appears on `orders.created` and is successfully processed


Watch the queues at http://localhost:15672/#/queues to see the message move through the retry ladder.

---

### Scenario 4 — Idempotency

Manually re-queue a message (simulating a duplicate delivery).

```bash
# Send an order and note the orderId in the response
ORDER_ID=$(curl -s -X POST http://localhost:8080/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"dan","product":"Thingamajig"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['orderId'])")

echo "orderId: $ORDER_ID"
```

Use the RabbitMQ management UI to clone/re-publish the message. The consumer will log:

```
Duplicate message for orderId=<id> — acking and skipping
```

---

## Key Design Decisions

### Why separate retry queues per TTL level?

RabbitMQ TTL on a queue applies to the **head of the queue** — if a longer-TTL message is behind a shorter one, the
shorter one is blocked (head-of-line blocking). Separate queues with fixed TTLs ensure each message expires
independently.

### Why `SELECT FOR UPDATE SKIP LOCKED`?

Allows multiple outbox scheduler instances to process disjoint batches concurrently without blocking each other or
blocking concurrent INSERTs (READ_COMMITTED isolation avoids gap locks).

### Why MANUAL ack mode?

The retry interceptor must be able to ack the original delivery and re-publish to the retry queue as a single logical
operation. With AUTO ack mode the container acks before the listener returns, making it impossible for the interceptor
to take control.

### Why `x-single-active-consumer` on retry queues?

Enforces FIFO within each retry queue. Without it, consumers could pick up messages out of order, causing a later retry
attempt to be processed before an earlier one.
