---
marp: true
theme: default
paginate: true
style: |
  /* ── Base ── */
  section {
    font-family: 'Arial', 'Helvetica', sans-serif;
    font-size: 26px;
    color: #1a1a2e;
    background: #ffffff;
    padding: 50px 60px;
  }

  /* ── Blue title slides ── */
  section.blue {
    background: #27B8D2;
    color: white;
  }
  section.blue h1,
  section.blue h2,
  section.blue h3 {
    color: white;
    border-bottom: none;
  }
  section.blue h3 { color: #F5D547; }

  /* ── Headings ── */
  h1 { font-size: 48px; font-weight: 800; color: #1a1a2e; margin-bottom: 8px; }
  h2 { font-size: 36px; font-weight: 700; color: #1a1a2e; border-bottom: 3px solid #27B8D2; padding-bottom: 8px; margin-bottom: 20px; }
  h3 { font-size: 26px; font-weight: 600; color: #27B8D2; }

  /* ── Code blocks ── */
  code {
    font-size: 20px;
    background: #f0f9fb;
    color: #1a1a2e;
    border-radius: 4px;
    padding: 2px 6px;
  }
  pre {
    font-size: 17px;
    background: #f0f9fb;
    border-left: 4px solid #27B8D2;
    border-radius: 6px;
    padding: 14px 18px;
    color: #1a1a2e;
  }
  section.blue pre {
    background: rgba(255,255,255,0.15);
    border-left-color: #F5D547;
    color: white;
  }
  section.blue code {
    background: rgba(255,255,255,0.2);
    color: white;
  }

  /* ── Tables ── */
  table { font-size: 22px; border-collapse: collapse; width: 100%; }
  th {
    background: #27B8D2;
    color: white;
    padding: 10px 14px;
    text-align: left;
  }
  td { padding: 8px 14px; border-bottom: 1px solid #e8f7fa; }
  tr:nth-child(even) td { background: #f0f9fb; }

  /* ── Blockquote (highlight box) ── */
  blockquote {
    font-size: 22px;
    background: #FFF9DB;
    border-left: 5px solid #F5D547;
    padding: 14px 20px;
    border-radius: 0 8px 8px 0;
    color: #1a1a2e;
    margin: 16px 0;
  }
  section.blue blockquote {
    background: rgba(255,255,255,0.15);
    border-left-color: #F5D547;
    color: white;
  }

  /* ── Inline emphasis ── */
  strong { color: #1a85a0; }
  section.blue strong { color: #F5D547; }
  em { color: #718096; }
  section.blue em { color: rgba(255,255,255,0.8); }

  /* ── Pagination ── */
  section::after { font-size: 16px; color: #aaa; }
  section.blue::after { color: rgba(255,255,255,0.5); }

  /* ── Images ── */
  img[alt~="center"] { display: block; margin: 0 auto; }
  img[alt~="sheep"]  { display: block; margin: 0 auto; max-height: 400px; }
  img[alt~="logo-topright"] { position: absolute; top: 30px; right: 40px; }

---

<!-- _class: blue -->

![logo-topright height:30px](meshcloud-logo-white.svg)

# No Message Left Behind

### A Practical Guide to Resilient but Simple Messaging

<br>

*Deliver messages reliably, simplify your life*

---

## About me

- Professional Software Engineer by heart since 2008 and at meshcloud since 2017
- Working on delivering a good and maintainable product every day with the team

---

## Why this talk?

- We needed a **simple but reliable** messaging solution at meshcloud
- Pieces existed in blog posts — wiring them together end-to-end took real effort
- A **complete, working example** — not just the happy path

<!--
The pieces existed scattered across blog posts, but no single complete example showed how to wire everything together end-to-end in Spring Boot.
-->

---

## Setting the Scene

```
  ┌───────────────┐                              ┌──────────────────┐
  │               │      "Order Created"         │                  │
  │ Order Service │  ───────────────────────►    │ Shipping Service │
  │               │        Message Queue         │                  │
  └───────────────┘                              └──────────────────┘
         writes                                        reads
       orders DB                                    ships orders
```

> **But what can go wrong?** *Spoiler: quite a lot.*

<!--
Classic e-commerce flow: two services, one message queue. An order comes in, we publish a message, shipping picks it up. Sounds simple — but there are several failure scenarios to address.
-->

---

## The Happy Path

```
   ① Customer places order
                │
                ▼
   ② Order Service saves to DB
                │
                ▼
   ③ Order Service publishes "ORDER_CREATED" to queue
                │
                ▼
   ④ Shipping Service receives message
                │
                ▼
   ⑤ Shipping Service initiates shipping  ✅
```

<!--
This works perfectly — as long as nothing ever fails. But we're building distributed systems. Things will fail. Let's walk through the failure scenarios one by one.
-->

---

<!-- _class: blue -->

## Failure Scenario 1

### The message never leaves the producer

```
   Order Service                  Broker                 Shipping Service
        │                           │                          │
        ├── save order to DB  ✅    │                          │
        │                           │                          │
        ├── publish message ──── ✗  │                          │
        │        broker down!       │                          │
        │        network error!     │                          │
        │        app crashes!       │                          │
        │                           │                          │
```

**Order saved. Shipping never notified. Silent data loss.**

<!--
The order is saved and the customer got a confirmation — but the shipping service never finds out about it.
-->

---

## Why does this happen?

Two operations. **Not atomic.**

```
  ┌────── DB Transaction ──────┐
  │  INSERT INTO orders (…)    │        publish("ORDER_CREATED")
  └────────────────────────────┘              ▲
                                              │
                                    NOT part of the transaction!
```

> You can't transact across two different systems (DB + broker).

<!--
The database commit and the message publish are two separate operations. If either one fails after the other succeeds, we're in an inconsistent state. You might think: "Just put the publish inside the transaction!" But the broker is a different system — you can't transact across both.
-->

---

## Naive fix: Publish first, then save?

```
  publish("ORDER_CREATED")   ✅  message is on the queue
  INSERT INTO orders (…)     ✗   DB fails!
```

→ Shipping processes an order that **doesn't exist** — a phantom event.

> **There is no safe ordering.**
> DB + broker = two systems, no atomic update.

<!--
Swapping the order has the same problem in the other direction. The fundamental issue is two systems that need to be updated atomically — which isn't possible without the outbox pattern.
-->

---

## Solution: The Transactional Outbox

> Write the event to an **outbox table in the same DB transaction**

```
  ┌─────────── Single DB Transaction ───────────┐
  │                                             │
  │  INSERT INTO orders (…)                     │
  │  INSERT INTO outbound_events (…)            │
  │                                             │
  └─────────────────────────────────────────────┘
```

A background **scheduler** (every ~1s):
1. Reads unsent events
2. Publishes to broker
3. Marks as sent

<!--
Core idea: Don't publish to the broker directly. Instead, write the event to an outbox table in the same database, in the same transaction. Either both rows commit, or neither does — the event can never be lost.
-->

---

## Outbox: How it looks architecturally

```
  ┌──────────────── Order Service ─────────────────┐
  │                                                │
  │   POST /orders                                 │
  │        │                                       │
  │        ▼                                       │
  │   ┌──────────────────────────────────────┐     │
  │   │ BEGIN TRANSACTION                    │     │
  │   │   INSERT INTO orders (…)             │     │
  │   │   INSERT INTO outbound_events (…)    │     │
  │   │ COMMIT                               │     │
  │   └──────────────────────────────────────┘     │
  │                                                │
  │   Scheduler (every 1s)                         │
  │      │                                         │
  │      ├── SELECT unsent events    │
  │      ├── Publish to broker                     │
  │      └── Mark as sent                          │
  └────────────────────────────────────────────────┘
```

<!--
The publish is fully decoupled from the business operation. If the broker is down, events simply queue up in the database and get published once it's back.
-->

---

## ✅ Producer Problem — Solved

| Concern            | How the Outbox handles it              |
|--------------------|----------------------------------------|
| Atomicity          | Same DB transaction as business data   |
| Broker outage      | Events queue up in DB, published later |
| App crash          | Unsent events are retried by scheduler |
| Multiple instances | `SKIP LOCKED` — no conflicts           |
| Poison events      | Retry counter with circuit breaker     |

<!--
Rule of thumb: Never publish to a broker outside of a transaction. Save the event where you save the data.
-->

---

<!-- _class: blue -->

## Failure Scenario 2

### The consumer can't process the message

```
   Order Service            Broker            Shipping Service
        │                     │                      │
        │                     │  ──── deliver ─────► │
        │                     │                      ├── parse ✅
        │                     │                      ├── ✗ temporary failure!
        │                     │                      │
```

**Message delivered. Processing failed with temporary failure (e.g. timeout, network issue, etc)**

<!--
The message was delivered, but the consumer failed to process it. Maybe a downstream API is down. Maybe the database is overloaded. Maybe it's a transient network blip.
-->

---

## Naive Thought

"RabbitMq will handle it for us automatically."

No, in a default set up it may put it on a Dead Letter Queue and you have to manualyl handle it

<!--
Unlike the producer problem, the message was received. The consumer just couldn't finish the work — this time. What we need: retry the message — but not immediately. Give the dependency time to recover. With increasing delays between attempts.
-->

---

## Naive fix: Requeue immediately

```
  Message fails → requeue → immediate retry → fails again → requeue → …
```

**Problems:**
- Burns CPU & network for nothing
- Overwhelms an already struggling dependency
- Pollutes logs with thousands of identical errors

> We need to **back off** between retries.

<!--
This creates a hot retry loop. It doesn't give the downstream system time to recover.
-->

---

## What we really want: Exponential backoff

```
  Attempt 1 fails → wait  1 min  → retry
  Attempt 2 fails → wait  5 min  → retry
  Attempt 3 fails → wait 30 min  → retry
  Attempt 4 fails → wait  1 hr   → retry
  Attempt 5 fails → wait  8 hrs  → retry (or give up)
```

> **RabbitMQ has no native delayed delivery.**
> → Build it from standard features.

<!--
This stops the hot loop and gives dependencies room to recover. But RabbitMQ doesn't support delayed delivery natively — so we need to build delays ourselves using DLX + TTL.
-->

---

## The insight: DLX + TTL = a delay line

### Dead-Letter Exchanges (DLX)
When a message **expires or is rejected** → routed to a configured exchange automatically.

### Message TTL
A queue can **expire messages** after a fixed duration.

> **TTL + DLX = delay line:**
> Message enters → sits for TTL → moves to next queue.
> No plugins. Standard RabbitMQ only.

<!--
Combining these two primitives gives us exactly what we need. A queue with TTL + DLX acts as a delay line: the message enters, waits for exactly the TTL, and is then automatically forwarded to the next exchange/queue.
-->

---

## Solution: The Retry Ladder

```
  Consumer fails
       │
       ▼
  ┌─────────────────── Retry Ladder ───────────────────┐
  │                                                    │
  │   retry-1   ──── wait  1 min ──┐                   │
  │   retry-2   ──── wait  5 min ──┤                   │
  │   retry-3   ──── wait 30 min ──┼──► DLX expires    │
  │   retry-4   ──── wait  1 hr  ──┤         │         │
  │   retry-5   ──── wait  8 hrs ──┘         │         │
  │                                          ▼         │
  │                               retry-wait-ended     │
  │                                          │         │
  └──────────────────────────────────────────┼─────────┘
                                             │
                            re-publish to original queue
                                             │
                                             ▼
                                     Consumer retries
```

<!--
Attempt 1: Consumer receives message → fails. Route to retry-1. Message sits for 1 minute.
Attempt 2: Message returns via DLX → consumer retries → fails again. Route to retry-2. Message sits for 5 minutes. And so on.
After all retries exhausted: Message stays in the last retry queue, acting as a soft dead-letter queue where operators can inspect and intervene.
The system gives the failing dependency time to recover — most transient issues resolve within the first few retries.
-->

---

## Why separate queues per TTL level?

> **TTL expiry is only checked at the head of the queue (FIFO).**

```
  Single queue with mixed TTLs:
  ┌─────────────────────────────────────────────┐
  │  msg(30min)  │  msg(5min)  │  msg(1min)     │
  └─────────────────────────────────────────────┘
  ▲ head                                   tail ▲

  msg(1min) can't expire until msg(30min) does! ← head-of-line blocking
```

**→ One dedicated queue per TTL level.**

<!--
A critical RabbitMQ detail most people don't know: if you mix different TTLs in one queue, shorter delays get stuck behind longer ones. The solution: one dedicated queue per TTL level, so every message expires on time.
-->

---

## The `retry-wait-ended` queue explained

All 5 retry queues dead-letter their **TTL-expired messages to one central queue**:

A `@RabbitListener` on `retry-wait-ended` re-publishes each message back to where it came from:

```kotlin
@RabbitListener(queues = ["retry-wait-ended"])
fun onMessage(message: Message) {
    val exchange   = headers["x-original-exchange"]    as String
    val routingKey = headers["x-original-routing-key"] as String
    rabbitTemplate.send(exchange, routingKey, message) // back to original queue
}
```

> The interceptor **stamps `x-original-exchange` + `x-original-routing-key` on the very first failure** — that's how the listener always knows where to route back.

<!--
The retry-wait-ended queue is a single funnel. All five retry queues drain into it when their TTL fires. The RetryQueueInterceptor writes x-original-exchange and x-original-routing-key headers when it handles the very first failure. Those headers travel with the message through every hop of the retry ladder. When the message finally reaches retry-wait-ended, the RetryWaitEndedRabbitListener reads those two headers and re-publishes to the original destination. The x-retried-count header is also preserved, so when the consumer attempts processing again the interceptor knows how many retries have already happened.
-->

---

## A note on acknowledgment

```
  Auto-Ack mode:                      Manual-Ack mode:
  ┌─────────────────────┐             ┌─────────────────────────┐
  │ Broker acks message │             │ Listener processes      │
  │ BEFORE listener runs│             │ Interceptor catches     │
  │                     │             │ Interceptor reroutes    │
  │ Too late to reroute!│             │ THEN acks               │
  └─────────────────────┘             └─────────────────────────┘
```

**→ Manual ack is essential** for retry rerouting.

<!--
For the retry rerouting to work, the consumer needs control over acknowledgment. Auto-Ack mode acknowledges the message before the listener runs — too late to reroute. Manual acknowledgment lets the consumer decide whether to ack (success) or reroute to a retry queue (failure).
-->

---

## How is the routing done?

An **interceptor** wraps around your message listener:

```
  Message arrives
           │
           ▼
  ┌── Interceptor ──────────────────────────────────┐
  │   Try: run the actual listener logic             │
  │                                                  │
  │   Catch: on failure →                            │
  │     1. Read retry count from headers             │
  │     2. Pick the right retry queue                │
  │     3. Stamp original exchange + routing key     │
  │     4. Send to retry queue                       │
  │     5. Ack the original delivery                 │
  └──────────────────────────────────────────────────┘
```

<!--
This is AOP — a cross-cutting concern wired in as middleware, not scattered through your business logic. The interceptor handles all retry routing transparently.
-->

---

## Two layers of retry

```
  Message arrives
       │
       ▼
  ┌─── Layer 1: In-Process Retry ─────────────────┐
  │  Attempt 1 → fail                             │
  │  Attempt 2 (after 0.5s) → fail                │   Fast. In-memory.
  │  Attempt 3 (after 1.0s) → fail                │   For network blips.
  │  ✗ all attempts exhausted                     │
  └───────────────────────────────────────────────┘
       │
       ▼
  ┌─── Layer 2: Retry Ladder (queue-based) ────────┐
  │  retry-1 (1 min) → retry-2 (5 min) → …         │   Durable. Escalating.
  │  → retry-3 (30 min) → retry-4 (1h) → retry-5   │   For real outages.
  └────────────────────────────────────────────────┘
```

---

## ✅ Consumer Problem — Solved

| Concern               | How the Retry Ladder handles it                        |
|-----------------------|--------------------------------------------------------|
| Transient failure     | In-process retry (3 quick attempts)                    |
| Sustained outage      | Queue-based retry with escalating delays               |
| Hot retry loops       | Exponential backoff: 1 min → 5 min → 30 min → 1h → 8h |
| No built-in delay     | DLX + TTL = delay queues using standard features       |
| Head-of-line blocking | Separate queue per TTL level                           |
| Dead letters          | Last retry queue acts as soft DLQ for human review     |

<!--
The system heals itself. Most issues resolve within the first few retries. Operators only get involved for the rare persistent failure.
-->

---

<!-- _class: blue -->

## One More Thing

### At-least-once means duplicates

```
  First delivery:    process order → initiate shipping  ✅
  Duplicate:         "Already shipped this order" → skip, ack  ✅
```

**Your consumer must be idempotent.**

> Track processed IDs (DB or cache) — skip duplicates.

<!--
Both patterns guarantee delivery but not exactly-once. The outbox might re-publish an event if the app crashes between publishing and marking the event as sent. The retry ladder re-delivers messages by design.
-->

---

## The Complete Picture

```
 ┌──── Order Service ─────────────────────────────┐
 │                                                │
 │   ┌────────── DB Transaction ──────────┐       │
 │   │  Save order                        │       │
 │   │  Save event to outbox table        │       │
 │   └────────────────────────────────────┘       │
 │                                                │
 │   Scheduler polls outbox → publishes to broker │
 └──────────────────────────┬─────────────────────┘
                            │
                     Message Queue
                            │
 ┌──── Shipping Service ────┼─────────────────────┐
 │                          ▼                     │
 │   ┌─ Layer 1: Quick retry (in-process) ──┐     │
 │   └──────────────────────────────────────┘     │
 │                    │ if exhausted              │
 │   ┌─ Layer 2: Retry ladder (DLX + TTL)───┐     │
 │   │  1 min → 5 min → 30 min → 1h → 8h    │     │
 │   └──────────────────────────────────────┘     │
 │                                                │
 │   Idempotency guard → process or skip          │
 └────────────────────────────────────────────────┘
```

---

## Production Reality

### 🙂 It's boring (in the best way)
No special infrastructure · Standard RabbitMQ only · Minimal maintenance

### 📊 What to monitor
- **Outbox table** growing? → broker issue
- **retry-3/4/5** filling up? → real outage
- **retry-5 depth** > 0? → human intervention needed

### ⚠️ What it's not
- Not exactly-once
- Not zero-latency *(outbox ~1s; retries add minutes)*
- Not a replacement for event sourcing

<!--
We run this exact setup in production. Minimal maintenance, no special infrastructure, standard RabbitMQ features only — no plugins, no Debezium, no CDC.
-->

---

## Key Takeaways

<br>

1. **Save the event where you save the data** — transactional outbox
2. **Retry in two layers** — fast in-process + durable queue-based
3. **Back off exponentially** — separate queues per TTL, no head-of-line blocking
4. **Design for idempotency** — at-least-once means duplicates
5. **Keep it simple** — standard features, no extra infrastructure

<!--
1. Never publish to a broker outside of a DB transaction.
2. Fast in-process retries for blips. Durable queue-based retries for outages.
3. Separate queues per TTL level to avoid head-of-line blocking in RabbitMQ.
4. At-least-once delivery means duplicates will happen. Always.
5. Standard features. No extra infrastructure. Remarkable stability.
-->

---

<!-- _class: blue -->

## Demo Time! 🚀

Let's see it in action
![sheep center height:200px](sheep-demo.png)

---

<!-- _class: blue -->

![height:40px](meshcloud-logo-white.svg)

<br><br>

# Thank You!

Full source code + setup instructions on GitHub:

**`github.com/…/rabbitmq-demo`**

<br>

### Further reading

- RabbitMQ Dead-Letter Exchanges — **rabbitmq.com/docs/dlx**
- Transactional Outbox Pattern — **microservices.io/patterns/data/transactional-outbox**

---

# Backup Slides

The following slides address some further edge cases that were not covered before.

---

## Outbox: What about concurrency?

**`SELECT … FOR UPDATE SKIP LOCKED`** ensures:

- Each instance locks only the rows it claims
- Other instances **skip** locked rows — no waiting, no double-publishing

```
  Instance A:  locks [1, 2, 3] → publishes → marks sent
  Instance B:  skips [1, 2, 3] → locks [4, 5, 6] → publishes
  Instance C:  skips [1–6] → nothing to do → sleeps
```

Scales horizontally with **zero coordination overhead**.

<!--
Multiple instances of the service can poll the outbox concurrently. SKIP LOCKED ensures no double-publishing, no blocking, no deadlocks.
-->

---

## Outbox: What about failures?

```
  Publish failed?
       │
       ▼
  Increment retry counter → next scheduler tick (1s) retries.
  After 100 failures → poison event → skip + alert.

  App crashes between publish and "mark sent"?
       │
       ▼
  Event still marked unsent → scheduler picks it up again.
  Consumer handles the resulting duplicate (idempotency).
```

> The outbox makes events **durable** — survives crashes, restarts, and broker outages.

<!--
Key insight: The outbox makes the event durable in your database. It survives app crashes, deployment restarts, and broker outages. If the app crashes between publish and mark-as-sent, the event will be re-published — the consumer must handle the resulting duplicate.
-->
