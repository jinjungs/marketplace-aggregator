# Marketplace Aggregator — Architecture

## Overview

A system where a seller registers a product once, it gets automatically published to multiple marketplaces (eBay, etc.), and all subsequent events — sales, comments — are aggregated into a single activity feed.

Reference marketplace: **eBay**
- OAuth 2.0 authentication, REST Inventory API
- Asynchronous product listing processing
- Webhook delivery via Platform Notification
- Real integration is not implemented — replaced with a mock

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                           AWS Cloud                                  │
│                                                                      │
│  ┌──────────┐    ┌─────────────┐                                    │
│  │    S3    │    │ CloudFront  │◄────── Seller Browser               │
│  │ (HTML/JS)│◄───│   (CDN)     │                                    │
│  └──────────┘    └──────┬──────┘                                    │
│                         │ API requests                               │
│                         ▼                                           │
│                  ┌─────────────┐                                    │
│                  │ API Gateway │                                    │
│                  └──────┬──────┘                                    │
│                         │                                           │
│                         ▼                                           │
│              ┌──────────────────────┐                               │
│              │  Lambda (Spring Boot) │                               │
│              │  + SnapStart (Java21) │                               │
│              │                      │                               │
│              │  POST /listings       │                               │
│              │  GET  /listings       │                               │
│              │  POST /webhooks       │                               │
│              └────────┬─────────────┘                               │
│                       │                                             │
│           ┌───────────┼───────────────────┐                        │
│           │           │                   │                        │
│           ▼           ▼                   ▼                        │
│    ┌────────────┐ ┌──────────┐  ┌──────────────────┐              │
│    │  DynamoDB  │ │ Secrets  │  │  SQS             │              │
│    │            │ │ Manager  │  │  (publish queue) │              │
│    │ listings   │ │          │  └────────┬─────────┘              │
│    │ activity   │ │ - webhook│           │                         │
│    │ _events    │ │   secret │           │                         │
│    │            │ │ - mock   │           │                         │
│    └────────────┘ │   api key│           │                         │
│                   └──────────┘           │                         │
│                                          │                         │
│  ┌───────────────────────────────────────┼───────────────────────┐ │
│  │  Mock Marketplace (separate module)   │                       │ │
│  │                                       ▼                       │ │
│  │  Separate API Gateway                                          │ │
│  │      │                                                         │ │
│  │      ▼                                                         │ │
│  │  ┌────────────────────┐                                        │ │
│  │  │ Lambda             │  POST /mock/listings/publish           │ │
│  │  │ (Publish Receiver) │  ← called by our SQS consumer         │ │
│  │  └─────────┬──────────┘                                        │ │
│  │            │ 202 Accepted + enqueues to its own Delay Queue    │ │
│  │            ▼                                                   │ │
│  │  ┌────────────────────┐                                        │ │
│  │  │ SQS Delay Queue    │  5~30s delay (async simulation)        │ │
│  │  └─────────┬──────────┘                                        │ │
│  │            │                                                   │ │
│  │            ▼                                                   │ │
│  │  ┌────────────────────┐                                        │ │
│  │  │ Lambda             │                                        │ │
│  │  │ (Event Emitter)    │                                        │ │
│  │  │                    │                                        │ │
│  │  │ 80% → send webhook │                                        │ │
│  │  │ 20% → fail → DLQ   │                                        │ │
│  │  └─────────┬──────────┘                                        │ │
│  └────────────┼───────────────────────────────────────────────── ┘ │
│               │ HMAC-signed POST /webhooks                          │
│               ▼                                                     │
│       Main Lambda (verify signature → write to DynamoDB             │
│                    activity_events)                                  │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Data Flow (step-by-step)

```
① Seller submits a product registration form (title, description, price)
        │
        ▼
② POST /listings
   → Save to DynamoDB listings table (status: PENDING)
   → Enqueue message to SQS publish queue
   → Return 200 immediately  ← seller does not wait
        │
        ▼  (Async ①: how we send requests to the marketplace)
③ SQS Consumer Lambda
   → Call mock marketplace POST /mock/listings/publish
        │
        ▼
④ Mock Marketplace Publish Receiver
   → Respond 202 Accepted
   → Enqueue to its internal SQS Delay Queue
        │
        ▼  (5~30s later, Async ②: how the marketplace notifies us)
⑤ Mock Event Emitter Lambda
   → 80%: attach HMAC signature and POST /webhooks
          body: { "event": "item_sold" } or { "event": "new_comment" }
   → 20%: fail → SQS auto-retry (up to 3 times) → DLQ
        │
        ▼
⑥ POST /webhooks received
   → Verify HMAC signature (401 if mismatch)
   → Write to DynamoDB activity_events table
        │
        ▼
⑦ Seller calls GET /listings
   → Returns listing list + activity feed per listing
```

---

## AWS Service Choices

| Service | Alternative | Reason |
|---------|-------------|--------|
| Lambda + SnapStart | ECS Fargate | Fargate charges 24/7; Lambda is pay-per-request. SnapStart solves Java cold start |
| DynamoDB on-demand | Aurora Serverless | Aurora has a minimum charge; DynamoDB on-demand is $0 with no traffic |
| SQS | Kafka (MSK) | Kafka cluster itself costs hundreds of dollars/month; SQS first 1M requests free |
| API Gateway HTTP API | REST API | HTTP API is ~70% cheaper than REST API |
| S3 + CloudFront | EC2 + Nginx | No server needed for static file serving; nearly free |
| CDK (TypeScript) | SAM / Terraform | Single `cdk deploy` command; IaC as code |
| Secrets Manager | Hardcoded env vars | Prevents secret exposure in code; explicitly required by the assignment |

---

## Project Structure

```
marketplace-aggregator/
├── APPROACH.md                   # Assignment write-up
├── README.md                     # Deploy/teardown/cost guide
├── architecture.md               # Architecture doc (Korean)
├── architecture.en.md            # Architecture doc (English)
├── cdk/                          # AWS infrastructure (TypeScript)
│   ├── package.json
│   └── lib/
│       └── marketplace-stack.ts  # Lambda, DynamoDB, SQS, CloudFront, etc.
├── backend/                      # Spring Boot main app (Java 21)
│   ├── pom.xml
│   └── src/main/java/com/marketplace/
│       ├── listing/              # POST /listings, GET /listings
│       ├── webhook/              # POST /webhooks (receive + verify)
│       ├── activity/             # activity feed query
│       └── common/               # DynamoDB client, HMAC util
└── mock-marketplace/             # Separate Lambda module (Java 21)
    ├── pom.xml
    └── src/main/java/com/marketplace/mock/
        ├── PublishReceiver.java   # POST /mock/listings/publish
        └── EventEmitter.java     # SQS → webhook dispatch (20% failure rate)
```

---

## DynamoDB Table Design

### listings table (product source data)
```
PK: listingId (UUID)

{
  "listingId":   "listing-001",
  "sellerId":    "seller-001",
  "title":       "MacBook Pro 14-inch",
  "description": "2023 model, good condition",
  "price":       1500000,
  "createdAt":   "2026-04-23T10:00:00Z",
  "updatedAt":   "2026-04-23T10:00:00Z"
}
```

> No status field. Status varies per marketplace and is managed in the marketplace_listings table.

### marketplace_listings table (per-marketplace publish status)
```
PK: listingId
SK: marketplaceId

{
  "listingId":         "listing-001",
  "marketplaceId":     "ebay",
  "status":            "PENDING | PUBLISHED | FAILED",
  "externalListingId": "ebay-12345",
  "publishedAt":       "2026-04-23T10:05:00Z",
  "failReason":        "",
  "createdAt":         "2026-04-23T10:00:00Z",
  "updatedAt":         "2026-04-23T10:05:00Z"
}
```

> Supports independent status per marketplace (e.g., PUBLISHED on eBay, FAILED on Facebook).

### activity_events table
```
PK: listingId
SK: timestamp#eventId  (sortable by time)

{
  "listingId":     "listing-001",
  "eventId":       "evt-001",
  "marketplaceId": "ebay",
  "timestamp":     "2026-04-23T10:05:00Z",
  "eventType":     "item_sold | new_comment | publish_failed",
  "data":          { "buyerName": "John Doe", "price": 1500000 }
}
```

> SK unchanged. The primary query pattern (fetch by listingId, sorted by time) is preserved.
> Per-marketplace filtering is handled at the application level if needed.

---

## Endpoint Definitions

### Main Backend

| Method | Path | Caller | Description |
|--------|------|--------|-------------|
| POST | /listings | Browser | Create listing + enqueue publish |
| GET | /listings | Browser | List listings + activity feed |
| POST | /webhooks | Mock Marketplace | Receive events (HMAC verification required) |

### Mock Marketplace

| Method | Path | Caller | Description |
|--------|------|--------|-------------|
| POST | /mock/listings/publish | Our SQS Consumer | Receive publish request → return 202 |

---

## Authentication Design

### Webhook HMAC Signature Verification (required — explicitly stated in assignment)

```
Setup:
  - Generate a random secret key
  - Store in Secrets Manager
  - Both mock marketplace Lambda and main Lambda read the same key

Sending (mock Event Emitter):
  signature = HMAC-SHA256(requestBody, secretKey)
  Attach header: X-Marketplace-Signature: sha256={signature}

Receiving and verifying (main /webhooks):
  expected = HMAC-SHA256(requestBody, secretKey)
  MessageDigest.isEqual(received, expected)  ← prevents timing attack
  If mismatch → 401 Unauthorized
```

> Use `MessageDigest.isEqual()` instead of `String.equals()`.
> Reason: Normal string comparison short-circuits on the first mismatch — an attacker can measure response time to infer the correct signature character by character (timing attack).

### Mock API Key (optional)

```
Store "mock-api-key-xxxx" in Secrets Manager
Our SQS Consumer attaches it when calling the mock endpoint:
Authorization: Bearer mock-api-key-xxxx

In production, this slot would hold an eBay OAuth access_token.
```

---

## Idempotency

### Core principle: listingId (PK) doubles as the idempotency key

DynamoDB Conditional Write atomicity is guaranteed only at the **item level (same PK)**.
Therefore the idempotency key must be the PK. In our design, `listingId` serves as both the PK and the idempotency key.

### Preventing duplicate listing creation — concurrent request flow

```
Client A ── POST /listings (listingId: "abc") ──► Lambda 1 starts
                                                        │
Client B ── POST /listings (listingId: "abc") ──► Lambda 2 starts
(network retry or double-click)                         │
                                                        │
                    ┌───────────────────────────────────┤
                    │                                   │
                    ▼                                   ▼
              [Lambda 1]                          [Lambda 2]
              Business logic                      Business logic
              validation, etc.                    validation, etc.
                    │                                   │
                    ▼                                   ▼
              DynamoDB                            DynamoDB
              Conditional Write attempt           Conditional Write attempt
              PUT item                            PUT item
              IF attribute_not_exists             IF attribute_not_exists
              (listingId)                         (listingId)
                    │                                   │
                    └──────────┬────────────────────────┘
                               │
                               ▼
                        ┌─────────────┐
                        │  DynamoDB   │
                        │  Atomic     │
                        │  processing │
                        │  (partition │
                        │   serialized)│
                        └──────┬──────┘
                               │
              ┌────────────────┴────────────────┐
              │                                 │
              ▼                                 ▼
        First request processed          Second request processed
              │                                 │
              ▼                                 ▼
        ✅ Write succeeds               ❌ ConditionalCheck
        item stored                        FailedException
              │                                 │
              ▼                                 ▼
        Enqueue SQS                      Return 409 Conflict
        publish message                  "already processed"
              │
              ▼
        Return 200 OK
```

> **Why this differs from RDB "uncommitted write" concerns:**
> DynamoDB conditional write is a single atomic operation — condition check and write happen together.
> Even if Lambda 1 is still in-flight (hasn't received the write response yet),
> DynamoDB has already committed the item internally, so Lambda 2 immediately receives ConditionalCheckFailedException.
> There is no window between "check" and "write" for another request to slip in.

### Preventing duplicate marketplace publish

```
When writing to activity_events:
  SK = timestamp#eventId
  eventId is the unique value from the webhook body
  → If the same eventId arrives twice, DynamoDB conditional write rejects it
```

---

## Multiple Marketplaces — Factory Pattern

Include marketplaceId in the SQS message and dispatch via factory in the Consumer Lambda.

```json
SQS message: { "listingId": "listing-001", "marketplaceId": "ebay" }
```

```java
interface MarketplaceAdapter {
    void publish(Listing listing);
}

class EbayAdapter implements MarketplaceAdapter { ... }
class FacebookAdapter implements MarketplaceAdapter { ... }

class MarketplaceAdapterFactory {
    MarketplaceAdapter get(String marketplaceId) {
        return switch (marketplaceId) {
            case "ebay"     -> new EbayAdapter();
            case "facebook" -> new FacebookAdapter();
            default -> throw new UnsupportedMarketplaceException(marketplaceId);
        };
    }
}

// Consumer Lambda
MarketplaceAdapter adapter = factory.get(message.getMarketplaceId());
adapter.publish(listing);
```

---

## Async Retry Strategy (SQS + DLQ)

```
POST /listings
      │
      ▼
  Lambda
  DynamoDB save
      │
      ▼
┌─────────────────────────────────────────────────────┐
│  SQS Publish Queue                                  │
│                                                     │
│  ┌─────────────────────────────────────────────┐   │
│  │  Message: { listingId: "abc", ... }         │   │
│  └─────────────────┬───────────────────────────┘   │
└────────────────────┼────────────────────────────────┘
                     │ trigger
                     ▼
               SQS Consumer Lambda
               calls mock marketplace
                     │
         ┌───────────┴───────────┐
         │                       │
         ▼                       ▼
      ✅ success             ❌ failure
         │                (timeout, 5xx, etc.)
         ▼                       │
    delete message               ▼
    (done)             message reappears after
                       visibility timeout
                               │
                     ┌─────────┴──────────┐
                     │  check retry count  │
                     └─────────┬──────────┘
                                │
              ┌─────────────────┴─────────────────┐
              │                                   │
              ▼                                   ▼
        retry < 3                           retry = 3
              │                            (maxReceiveCount)
              ▼                                   │
        retry processing                          ▼
                                    ┌─────────────────────┐
                                    │  DLQ                │
                                    │  (Dead Letter Queue)│
                                    └──────────┬──────────┘
                                               │
                                               ▼
                                    CloudWatch Alarm
                                               │
                                               ▼
                                    SNS → email / Slack
```

> **Visibility timeout:**
> When a message is delivered to a Lambda, it is temporarily hidden from other Lambdas.
> On success → message deleted. On failure or timeout → hidden state released, message retried.

### Two layers of SQS failure

```
Layer ①: Failed to reach the marketplace at all
  Consumer Lambda → calls marketplace
    → network error, timeout, 5xx response
    → Lambda throws exception → SQS retries  ← this is what SQS retry handles

Layer ②: Marketplace accepted (202) but failed internally
  Consumer Lambda → calls marketplace
    → 202 Accepted  ← Lambda considers this success, message deleted
    → webhook should arrive later, but if it never does → listing stuck PENDING
```

### publish_failed handling — no auto-retry

A publish_failed webhook means the marketplace processed the request but failed.
The cause could be a data error (missing required field, wrong category code, etc.).
Auto-retrying without knowing the cause risks an infinite loop and rate limit exhaustion.

```
publish_failed webhook received
  → marketplace_listings: status = FAILED
  → activity_events: publish_failed recorded
  → seller feed shows "publish failed"
  → seller retries manually (nice-to-have: retry button in UI)
  → developer notified (CloudWatch → Slack)
```

### PENDING stuck (webhook never arrives)

Since we build the mock marketplace ourselves, we can design the Mock DLQ Consumer to always send a publish_failed webhook. Stuck listings are structurally impossible in this setup.

> **Must-have for real eBay integration:**
> eBay internal failures can prevent webhooks from ever arriving.
> An EventBridge Scheduler polling for marketplace_listings stuck in PENDING beyond N minutes and marking them FAILED would be required.
> Not implemented here since the mock guarantees webhook delivery.

Mock Marketplace 20% failure rate follows the same pattern:
```
Event Emitter Lambda fails
  → SQS Delay Queue retries (up to 3 times)
  → 3 failures → Mock DLQ
  → Mock DLQ Consumer → sends publish_failed webhook
  → write "publish_failed" to activity_events
```

---

## Cost Estimate (10 sellers / 1k listings / 10k events / month)

| Service | Estimated Cost |
|---------|---------------|
| Lambda | ~$0 (1M requests/month free tier) |
| API Gateway HTTP API | ~$0.01 |
| DynamoDB on-demand | ~$0.03 |
| SQS | ~$0 (1M requests/month free) |
| S3 + CloudFront | ~$0.05 |
| Secrets Manager | ~$0.80 (2 secrets × $0.40) |
| **Total** | **~$1/month** |

First cost wall: millions of DynamoDB reads per month, or Lambda exceeding 1M invocations.

---

---

# Q&A — Concept Clarifications

---

**Q. What is CloudFront serving? Do we need to enable static hosting on S3?**

A. Yes. S3 is the origin file store (HTML, JS, CSS), and CloudFront sits in front, caching files on edge servers worldwide for fast delivery.
It's called "static hosting" because there is no server process — files are served as-is.
A React app built with `npm build` produces `.html` and `.js` files, and those are what get uploaded to S3.

---

**Q. Is Lambda like EC2? Is pay-per-request the reason to prefer it?**

A. Both are execution environments, but the concept differs.
EC2 is a virtual server you rent — it runs 24/7 and you pay by the hour.
Lambda executes your code only when a request arrives, then shuts down. You pay per request count and execution duration.
Since cost efficiency is an evaluation criterion for this assignment, Lambda is the right fit.

---

**Q. Doesn't Lambda have a cold start problem? Java is especially slow, right?**

A. Yes, it's a real problem. A normal Java Lambda cold start takes 3–8 seconds — significant.
The solution is **Lambda SnapStart**, supported from Java 21 onward.
At deployment, AWS snapshots the JVM boot state. On an incoming request, Lambda restores from that snapshot.
Cold start drops to ~200ms with no code changes — just a configuration setting.

---

**Q. Does "async mock marketplace" mean it receives Kafka messages or something?**

A. No. It's still a REST API call — the "async" refers to a different concept.
Synchronous: you request → wait for processing to complete → receive result.
Asynchronous: you request → receive "accepted (202)" → processing happens later → result delivered via webhook.
The real eBay API works this way too: a listing request returns "accepted" status, and the actual processing happens asynchronously.

---

**Q. What is a webhook?**

A. A webhook = the other party making an HTTP request to you.
A normal API call is you requesting something from another server. A webhook is the reverse.
When something happens on the other side, their server sends an HTTP POST to your server.
Analogy: calling your bank to ask for your balance is a normal API; the bank calling you to say "a deposit just arrived" is a webhook.
It's an architectural concept; the implementation is just an HTTP POST.

---

**Q. Can we use a single /webhooks endpoint? Shouldn't there be /item-sold, /comment, etc.?**

A. One /webhooks endpoint is fine. Put the event type in the body:
```json
{ "event": "item_sold", "listingId": "...", "data": {} }
{ "event": "new_comment", "listingId": "...", "data": {} }
```
The receiver branches on the `event` field.
Real eBay, Stripe, and GitHub all use this pattern.

---

**Q. Is DynamoDB like MongoDB?**

A. Both are NoSQL and store data in JSON form — similar in that regard.
The key difference is that DynamoDB is AWS-fully-managed with no servers, billed per request.
The core constraint: you can only query efficiently by PK (partition key) and SK (sort key).
Queries like "give me everything priced over $100" are inefficient (full table scan).
For this project, our queries are lookup-by-listingId and time-sorted activity — DynamoDB is sufficient.

---

**Q. Is SQS like Kafka? Is it inside AWS?**

A. Yes — same message queue concept, AWS-fully-managed.
Kafka requires you to install and operate your own cluster; it's powerful but complex.
SQS requires almost no configuration and is pay-per-request, making it far more suitable at this scale.
A Kafka cluster alone costs tens of thousands of won (hundreds of dollars) per month.

---

**Q. Is Secrets Manager inside AWS?**

A. Yes, it's an AWS-fully-managed service.
Secret values (DB passwords, API keys, webhook secrets) are stored here instead of hardcoded in the code. Lambda retrieves them at runtime.
It is also an explicitly stated requirement in this assignment.

---

**Q. Is the mock API key a token?**

A. It's not a token with expiry/refresh like real OAuth — it's a pre-agreed fixed string.
With real eBay you'd need to obtain an access token using client_id + client_secret, but since this is a mock, a string like "mock-api-key-1234" stored in Secrets Manager and sent in the header is sufficient.
It serves as a placeholder for "in production, this is where the eBay OAuth access_token would go."

---

**Q. How do the two sides exchange the webhook secret key?**

A. Real services like eBay issue it unilaterally through a developer portal, and the recipient stores it in Secrets Manager.
In this project, since it's a mock, we generate the secret ourselves, store it in Secrets Manager, and both the main Lambda and mock marketplace Lambda read from the same Secrets Manager in the same AWS account.

---

**Q. Is webhook signature verification just a string comparison?**

A. Yes, it's a string comparison — but you must not use `equals()`.
Use `MessageDigest.isEqual()` instead.
Reason: normal string comparison short-circuits on the first mismatch. An attacker can measure response times across millions of requests to infer the correct signature one character at a time (timing attack).
`MessageDigest.isEqual()` always compares the full string, so response time is constant and reveals nothing.

---

**Q. What authentication is required in this assignment?**

A. Webhook HMAC signature verification is required — it is explicitly listed in the evaluation criteria.
The `/webhooks` endpoint is open to the internet, so without verification, anyone can inject fake events.
Seller login (JWT/Cognito) is nice-to-have; mock API key is optional.

---

**Q. Do we also process our own publish requests to the marketplace through a message queue?**

A. Yes. There are two separate async flows.
Async ①: how we send to the marketplace — we return 200 to the seller immediately, put a message on SQS, and a separate Lambda asynchronously sends the publish request to the marketplace.
Async ②: how the marketplace notifies us — the marketplace sends a webhook to us after it finishes processing.
Reason: even if the marketplace API is slow or fails, the seller doesn't wait, and SQS handles retries automatically.

---

**Q. Does the client generate the idempotency key and put it in a header?**

A. There are two patterns.
Client generates a UUID and sends it in a header (Stripe does this) — advantage: allows safe retries when a network error prevents receiving the response.
Server generates a key from the business domain (your previous company's approach) — advantage: no client-side implementation needed, and the key is business-meaningful.
Both are valid.
In this project, the server generates the listingId as a UUID and uses DynamoDB conditional write (`attribute_not_exists(listingId)`) to prevent duplicates.

---

**Q. Is directly implementing async retry (MongoDB + processYN + batch) a standard approach?**

A. That approach is also a legitimate pattern — it has the advantage of being platform-agnostic and fully in your control.
On AWS, SQS + DLQ replaces that at the infrastructure level.
SQS manages retry counts, DLQ stores final-failure messages, and CloudWatch handles alerts — almost no code required.
For this assignment, using SQS + DLQ is the better choice since it demonstrates AWS proficiency.

---

**Q. Idempotency — how does DynamoDB handle concurrent requests? Is there a "before commit" problem like in RDB?**

A. DynamoDB conditional write is a single atomic operation — condition check and write happen together — so the RDB "before commit" problem does not apply.
In RDB with a SELECT-then-INSERT pattern, two transactions can both see "no row exists," then both attempt INSERT — a classic race condition.
DynamoDB serializes writes at the partition level, so even if Lambda 1 is still in-flight (hasn't received the write response), DynamoDB has already committed the item internally and Lambda 2 immediately receives ConditionalCheckFailedException.

---

**Q. How does RDB transaction isolation level affect the lock table idempotency pattern?**

A. It depends on whether you use approach A (INSERT + UNIQUE constraint) or approach B (SELECT → INSERT).

Approach A — INSERT + UNIQUE constraint:
Safe regardless of isolation level. The DB checks the unique constraint atomically at INSERT time.

Approach B — SELECT then INSERT (race condition possible):
```
READ UNCOMMITTED : Dirty Read occurs
  → Lambda 2 reads Lambda 1's uncommitted INSERT
  → If Lambda 1 rolls back, Lambda 2 already rejected the request → data loss

READ COMMITTED   : default in most DBs (PostgreSQL, etc.)
  → Lambda 2 only reads committed data
  → Both see "no row," both attempt INSERT
  → Without UNIQUE constraint → duplicate processing occurs

REPEATABLE READ  : MySQL InnoDB default
  → MySQL uses Gap Locks to block range INSERTs → some protection
  → PostgreSQL has no Gap Locks → still dangerous

SERIALIZABLE     : fully serialized
  → Safe, but frequent deadlocks and reduced throughput
```

Conclusion: if using SELECT-then-INSERT, always pair it with a UNIQUE constraint.
With a UNIQUE constraint, even at READ COMMITTED only one INSERT succeeds.

---

**Q. Does the idempotency key have to be the PK in DynamoDB Conditional Write?**

A. Yes. DynamoDB's `attribute_not_exists` atomicity guarantee applies only at the item level (same PK).
Two items with different PKs have no collision detection between them.

There are two options:
- Option 1: use the idempotency key as the PK (our design — `listingId` is both the PK and idempotency key)
- Option 2: create a separate idempotency table where the idempotency key is the PK; the listings table uses an internal UUID as its PK

This project uses Option 1.

---

**Q. Does configuring SQS automatically set up the Consumer Lambda?**

A. No. SQS Queue creation and Lambda Event Source Mapping are configured separately.
In CDK, `queue.addEventSource(new SqsEventSource(lambda))` connects them in one line, but internally they are two distinct resources.

---

**Q. Is visibility timeout there to prevent double-processing when there are multiple servers (Lambda instances)?**

A. Yes. Lambda automatically scales out to multiple instances under concurrent load, and visibility timeout prevents two instances from processing the same message simultaneously.
When a message is delivered to a Lambda, it is temporarily hidden from all other Lambdas.
On success → message deleted. On failure or timeout → hidden state released, message retried.

---

**Q. Can multiple services consume the same SQS message, like Kafka where both receiving and product services read the same order message?**

A. SQS delivers each message to exactly one consumer — this is different from Kafka's design philosophy.
Kafka allows multiple consumer groups to independently read the same message with separate offsets.
For SQS fan-out (multiple services consuming the same message), put SNS in front and fan out to multiple SQS queues: SNS → Queue A → Lambda A, SNS → Queue B → Lambda B.
Each queue processes independently and does not wait for the other.

---

**Q. Where is the SQS retry count tracked? Does Lambda code need to count it manually?**

A. SQS tracks it automatically. No counting needed in Lambda code.
Set `maxReceiveCount` in the SQS redrive policy; when that count is exceeded without the message being deleted, SQS automatically moves it to the DLQ.
The `ApproximateReceiveCount` attribute is available on the message if Lambda needs to read it, but it's not required.

---

**Q. What does each Lambda write to DynamoDB?**

A. Each Lambda has a distinct role:
- POST /listings Lambda → saves listing to listings table + enqueues SQS message
- SQS Consumer Lambda → calls mock marketplace, updates status in marketplace_listings table
- POST /webhooks Lambda → writes events to activity_events table

---

**Q. Should publish_failed events be retried automatically?**

A. No. Auto-retry is the wrong approach here.
The failure could be transient (marketplace temporarily down) or permanent (invalid listing data, wrong category code).
Retrying without knowing the cause risks an infinite loop and rate limit exhaustion.
Show the failure in the seller's feed and let the seller retry manually, or have a developer investigate the root cause.

---

**Q. Is the PENDING stuck case (webhook never arrives) a must-have?**

A. Nice-to-have for this assignment.
Since we build the mock marketplace ourselves, the Mock DLQ Consumer can always send a publish_failed webhook, making stuck listings structurally impossible.
For real eBay integration it would be must-have — eBay internal failures can silently prevent webhooks from arriving, and an EventBridge Scheduler timeout check would be the only safety net.

---

**Q. How does the listings table design change to support multiple marketplaces?**

A. Remove the status field from the listings table and introduce a separate marketplace_listings table.
Status is per-marketplace (eBay could be PUBLISHED while Facebook is FAILED), so it cannot live on the listing itself.
The marketplace_listings table uses PK=listingId, SK=marketplaceId to manage each marketplace's status independently.

---

**Q. Does INSERT + UNIQUE constraint work inside a transaction in RDB?**

A. Yes. UNIQUE constraint enforcement is atomic at INSERT time, regardless of the transaction boundary.
Even if the first transaction has not committed yet, the second transaction's INSERT will wait until the first commits or rolls back.
If the first commits → second gets Duplicate key error. If the first rolls back → second INSERT succeeds.

---

**Q. If lock_table rows are deleted after processing, is a second line of defense needed?**

A. Yes. Once the first request deletes the lock and commits, the lock_table no longer has the key, so the second request can INSERT successfully.
The UNIQUE constraint on the main table serves as the second line of defense, blocking duplicate processing at that point.
This means if you use the delete-after-processing pattern, a UNIQUE constraint on the main table is mandatory.
That conclusion leads to: the main table UNIQUE alone is sufficient — lock_table is optional.

---

**Q. Does lock_table keep accumulating rows?**

A. If rows are not deleted (kept as idempotency records), yes — they accumulate.
Option A: keep them and run a periodic batch to delete old entries. Leaves an audit trail of "this key was processed at this time."
Option B: delete after processing. Table stays lean but requires the main table UNIQUE as a second defense.
DynamoDB has a built-in TTL feature: set a ttl attribute to an expiry unix timestamp and DynamoDB auto-deletes the item.

---

**Q. What is the AOP-based lock management pattern?**

A. Create a custom annotation (e.g., `@Idempotent`) and use AOP to inject pre/post processing:
```
@Idempotent annotation
  Before:        INSERT lock
  After:         DELETE lock (on success)
  AfterThrowing: DELETE lock (on failure)
```
Business logic stays clean — just attach the annotation.
The edge case is forced process termination (OOM, kill -9): AfterThrowing does not run, and the lock remains.
A periodic batch job cleaning up stale locks handles this case.
Batch cleanup has an advantage over TTL: TTL blocks retries until expiry, whereas the batch runs on its own schedule (e.g., every minute) and releases locks faster.
