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

- We needed a simple but reliable messaging solution at meshcloud
- The pieces existed in blog posts, but wiring them together end-to-end in Spring Boot took real effort
- I want to share a **complete, working example** — not just the happy path

---

## Setting the Scene

Let's get into it with the most classic example.
We're building an e-commerce flow: **two services, one message queue.**

```
  ┌───────────────┐                              ┌──────────────────┐
  │               │      "Order Created"         │                  │
  │ Order Service │  ───────────────────────►    │ Shipping Service │
  │               │        Message Queue         │                  │
  └───────────────┘                              └──────────────────┘
         writes                                        reads
       orders DB                                    ships orders
```

Sounds simple. An order comes in, we publish a message, shipping picks it up.

**But what can go wrong?**

*Spoiler: quite a lot.*

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

This works perfectly — **as long as nothing ever fails.**

But we're building distributed systems. Things *will* fail.
Let's walk through the failure scenarios, one by one.

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

The order is saved. The customer got a confirmation.

But the shipping service **never finds out about it.** Silent data loss.

---

## Why does this happen?

We're trying to do **two things that aren't atomic:**

```
  ┌────── DB Transaction ──────┐
  │  INSERT INTO orders (…)    │        publish("ORDER_CREATED")
  └────────────────────────────┘              ▲
                                              │
                                    This is NOT part of
                                    the transaction!
```

The database commit and the message publish are **two separate operations**.

If either one fails after the other succeeds, we're in an inconsistent state.

> You might think: "Just put the publish inside the transaction!"
> But the broker is a different system — you can't transact across both.

---

## Naive fix: Publish first, then save?

```
  publish("ORDER_CREATED")   ✅  message is on the queue
  INSERT INTO orders (…)     ✗   DB fails!
```

Now shipping processes an order that **doesn't exist** — a phantom event.

Swap the order? Same problem, different direction.

> **There is no safe ordering.** The fundamental issue is that we have
> **two systems** (DB + broker) that we need to update **atomically**.

---

## Solution: The Transactional Outbox

**Core idea:** Don't publish to the broker directly. Instead, write the event
to an **outbox table in the same database**, in the **same transaction**.

```
  ┌─────────── Single DB Transaction ──────────┐
  │                                             │
  │  INSERT INTO orders (…)                     │
  │  INSERT INTO outbound_events (…, sent=false)│
  │                                             │
  └─────────────────────────────────────────────┘
```

**Either both rows commit, or neither does.** The event can never be lost.

A background **scheduler** (polling every ~1 second) then:

1. Reads unsent events from the outbox table
2. Publishes them to the broker
3. Marks them as sent if successfully delivered

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
  │      ├── SELECT unsent events (SKIP LOCKED)    │
  │      ├── Publish to broker                     │
  │      └── Mark as sent                          │
  └────────────────────────────────────────────────┘
```

The publish is **decoupled** from the business operation. If the broker is down,
events simply queue up in the database and get published once it's back.

---

## ✅ Producer Problem — Solved

| Concern            | How the Outbox handles it              |
|--------------------|----------------------------------------|
| Atomicity          | Same DB transaction as business data   |
| Broker outage      | Events queue up in DB, published later |
| App crash          | Unsent events are retried by scheduler |
| Multiple instances | `SKIP LOCKED` — no conflicts           |
| Poison events      | Retry counter with circuit breaker     |

> **Rule of thumb:** Never publish to a broker outside of a transaction.
> Save the event where you save the data.

---

<!-- _class: blue -->

## Failure Scenario 2

### The consumer can't process the message

```
   Order Service            Broker            Shipping Service
        │                     │                      │
        │                     │  ──── deliver ─────► │
        │                     │                      ├── parse ✅
        │                     │                      ├── call API ✗ timeout!
        │                     │                      │
```

The message was delivered, but the consumer failed to process it.

Maybe a downstream API is down. Maybe the database is overloaded.
Maybe it's a transient network blip.

**What should happen next?**

---

## Naive approach: Immediate requeue

```
  Message fails → requeue → immediate retry → fails again → requeue → …
```

This creates a **hot retry loop:**

- Burns CPU and network for nothing
- Doesn't give the downstream system time to recover
- Can overwhelm an already struggling dependency
- Pollutes logs with thousands of identical errors

> **What we really want:** Back off. Wait. Try again later.
> With increasing delays between each attempt.

---

## The challenge: RabbitMQ has no built-in delay

Unlike some systems, RabbitMQ can't natively say
*"deliver this message again in 5 minutes."*

But it *does* have two features we can combine:

### Dead-Letter Exchanges (DLX)

When a message expires or is rejected, RabbitMQ can automatically
route it to a different queue.

### Message TTL (Time-To-Live)

A queue can be configured so that messages expire after a fixed duration.

> **Insight:** A queue with TTL + DLX is essentially a **delay line.**
> Put a message in → wait → it automatically moves to the next queue.

---

## Solution: The Retry Ladder

Stack multiple delay queues with increasing TTLs:

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

---

## Retry Ladder: The flow step by step

**Attempt 1:** Consumer receives message → fails.
Route to **retry-1**. Message sits for 1 minute.

**Attempt 2:** Message returns via DLX → consumer retries → fails again.
Route to **retry-2**. Message sits for 5 minutes.

**Attempt 3:** Returns again → fails → **retry-3** (30 min).

**Attempt 4:** Returns again → fails → **retry-4** (1 hour).

**Attempt 5:** Returns again → fails → **retry-5** (8 hours).

**After all retries exhausted:** Message stays in the last retry queue.
It acts as a **soft dead-letter queue** where operators can inspect and intervene.

> The system gives the failing dependency **time to recover**.
> Most transient issues resolve within the first few retries.

---

## Why separate queues per TTL level?

A critical RabbitMQ detail most people don't know:

> **TTL expiry is checked only at the head of the queue (FIFO).**

```
  Single queue with mixed TTLs:
  ┌─────────────────────────────────────────────┐
  │  msg(30min)  │  msg(5min)  │  msg(1min)     │
  └─────────────────────────────────────────────┘
  ▲ head                                   tail ▲

  msg(1min) can't expire until msg(30min) does!
```

This causes **head-of-line blocking** — shorter delays are stuck behind longer ones.

**Solution:** One dedicated queue per TTL level.
Each queue has exactly one TTL → every message expires on time.

---

## How is the routing done?

The key piece is an **interceptor** that wraps around your message listener:

```
  Message arrives at consumer
           │
           ▼
  ┌── Interceptor ──────────────────────────────────┐
  │                                                  │
  │   Try: run the actual listener logic             │
  │                                                  │
  │   Catch: on failure →                            │
  │     1. Read retry count from message headers     │
  │     2. Pick the right retry queue                │
  │     3. Stamp original exchange + routing key     │
  │        into headers (so we know where to         │
  │        re-publish later)                         │
  │     4. Send message to retry queue               │
  │     5. Ack the original delivery                 │
  │                                                  │
  └──────────────────────────────────────────────────┘
```

This is AOP — a cross-cutting concern wired in as **middleware**, not scattered
through your business logic.

---

## Two layers of retry

In practice, we use **two retry strategies together:**

```
  Message arrives
       │
       ▼
  ┌─── Layer 1: In-Process Retry ─────────────────┐
  │                                               │
  │  Attempt 1 → fail                             │
  │  Attempt 2 (after 0.5s) → fail                │   Fast. In-memory.
  │  Attempt 3 (after 1.0s) → fail                │   For network blips.
  │  ✗ all attempts exhausted                     │
  └───────────────────────────────────────────────┘
       │
       ▼
  ┌─── Layer 2: Retry Ladder (queue-based) ────────┐
  │                                                │
  │  retry-1 (1 min) → retry-2 (5 min) → …         │   Durable. Escalating.
  │  → retry-3 (30 min) → retry-4 (1h) → retry-5   │   For real outages.
  └────────────────────────────────────────────────┘
```

---

## A note on acknowledgment

For the interceptor to reroute failed messages, it needs **control over acknowledgment**.

```
  Auto-Ack mode:                      Manual-Ack mode:
  ┌─────────────────────┐             ┌─────────────────────────┐
  │ Broker acks message │             │ Listener processes      │
  │ BEFORE listener runs│             │ Interceptor catches     │
  │                     │             │ Interceptor reroutes    │
  │ Too late to reroute!│             │ THEN acks               │
  └─────────────────────┘             └─────────────────────────┘
```

**Manual acknowledgment** is essential — it lets the interceptor
decide whether to ack (success) or reroute (failure).

---

## ✅ Consumer Problem — Solved

| Concern               | How the Retry Ladder handles it                       |
|-----------------------|-------------------------------------------------------|
| Transient failure     | In-process retry (3 quick attempts)                   |
| Sustained outage      | Queue-based retry with escalating delays              |
| Hot retry loops       | Exponential backoff: 1 min → 5 min → 30 min → 1h → 8h |
| No built-in delay     | DLX + TTL = delay queues using standard features      |
| Head-of-line blocking | Separate queue per TTL level                          |
| Dead letters          | Last retry queue acts as soft DLQ for human review    |

> **The system heals itself.** Most issues resolve within the first
> few retries. Operators only get involved for the rare persistent failure.

---

<!-- _class: blue -->

## One More Thing

### At-least-once means duplicates

Both patterns guarantee delivery but **not exactly-once.**

The outbox might re-publish an event if the app crashes between
publishing and marking the event as sent.

The retry ladder re-delivers messages by design.

**Your consumer must be idempotent.**

```
  First delivery:    process order → initiate shipping  ✅
  Duplicate:         "Already shipped this order" → skip, ack  ✅
```

In practice: track processed IDs (in DB or cache) and skip duplicates.

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

We run this exact setup in production. Here's what we've learned:

### It's boring (in the best way)

- Minimal maintenance. No special infrastructure.
- Standard RabbitMQ features only — no plugins, no Debezium, no CDC.

### What to monitor

- **Outbox table:** unsent events accumulating? → broker issue
- **Late retry queues (retry-3, 4, 5):** messages piling up? → real outage
- **retry-5 depth:** messages exhausted all retries → human intervention needed

### What it's not

- Not exactly-once *(that's a consensus problem)*
- Not zero-latency *(outbox polls every ~1s; retries add minutes)*
- Not a replacement for event sourcing

> **Good enough for the vast majority of use cases.**

---

## These Patterns Are Universal

The demo uses Spring Boot + RabbitMQ, but the concepts work anywhere:

| Building Block | RabbitMQ           | Kafka        | SQS                | Any Broker |
|----------------|--------------------|--------------|--------------------|------------|
| Delayed retry  | DLX + TTL          | Retry topic  | Visibility timeout | ✅          |
| Outbox table   | Same-DB TX         | Same-DB TX   | Same-DB TX         | ✅          |
| Poll & publish | Scheduler          | Scheduler    | Scheduler          | ✅          |
| Idempotency    | Business key dedup | Offset dedup | Message ID dedup   | ✅          |
| Dead letters   | Last retry queue   | DLT topic    | SQS DLQ            | ✅          |

> **The outbox table + poll loop works with any broker.**
> **The retry ladder concept works with any queue that supports TTL or delays.**

---

## Key Takeaways

<br>

**1. Save the event where you save the data.**
Use a transactional outbox — never publish outside a DB transaction.

**2. Retry in two layers.**
Fast in-process retries for blips. Durable queue-based retries for outages.

**3. Back off exponentially.**
Separate queues per TTL level to avoid head-of-line blocking in RabbitMQ.

**4. Design consumers for idempotency.**
At-least-once delivery means duplicates will happen. Always.

**5. Keep it simple.**
Standard features. No extra infrastructure. Remarkable stability.

---

<!-- _class: blue -->

## Demo Time! 🚀

Let's see it in action — four scenarios:

1. **Happy path** — order created, event published, shipping ships
2. **Broker down** — outbox retains the event, scheduler retries
3. **Consumer fails** — retry ladder with exponential backoff
4. **Duplicate delivery** — idempotency guard skips re-processed messages

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

<br>

| Producer side                 | Consumer side                       |
|-------------------------------|-------------------------------------|
| Outbox in same DB             | Retry ladder (DLX + TTL)            |
| Scheduler polls + publishes   | Interceptor reroutes failures       |
| `SKIP LOCKED` for concurrency | Manual ack for control              |
| At-least-once publish         | At-least-once consume + idempotency |

---

# Backup Slides

The following slides address some further edge cases that were not covered before.

---

## Outbox: What about concurrency?

Multiple instances of the service can poll the outbox concurrently.

**`SELECT … FOR UPDATE SKIP LOCKED`** ensures:

- Each instance locks only the rows it claims
- Other instances **skip** locked rows instead of waiting
- No double-publishing, no blocking, no deadlocks

```
  Instance A:  locks events [1, 2, 3] → publishes → marks sent
  Instance B:  skips [1, 2, 3] → locks events [4, 5, 6] → publishes
  Instance C:  skips [1–6] → nothing to do → sleeps
```

Scales horizontally with zero coordination.

---

## Outbox: What about failures?

```
  Publish failed?
       │
       ▼
  Increment retry counter, move to back of queue.
  Next scheduler tick (1s) → retry.
       │
       ▼
  After 100 failures → poison event → skip + alert.

  App crashes between publish and "mark sent"?
       │
       ▼
  Event is still marked unsent → scheduler picks it up again.
  Consumer must handle the resulting duplicate (more on that later).
```

**Key insight:** The outbox makes the event **durable** in your database.
It survives app crashes, deployment restarts, and broker outages.
