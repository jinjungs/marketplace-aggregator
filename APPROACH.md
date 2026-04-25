# Marketplace Aggregator — Approach

## Approach Summary

The system uses a **classic message-driven API architecture** rather than an agentic runtime. Marketplace publishing is a deterministic integration workflow: accept a listing, persist it, enqueue a publish request, receive an asynchronous marketplace callback, verify it, and append it to the seller's activity feed. 

---

## Architecture

```
Browser
  │
  ▼
CloudFront ──► S3 (HTML/JS)
  │
  ▼ API calls
API Gateway (REST API)
  │
  ▼
Lambda – Spring Boot (Java 21)
  ├── POST /listings  →  DynamoDB (listings, marketplace_listings)
  │                  →  SQS publish queue
  ├── GET  /listings, /listings/{id}  →  DynamoDB read
  └── POST /webhooks  →  HMAC verify  →  DynamoDB (activity_events)
                                       →  update marketplace_listings status

SQS publish queue
  └── Lambda (SQS consumer)
        └── POST mock-marketplace/listings/publish
              └── 202 Accepted → SQS delay queue (5–30 s)
                    └── Lambda (event emitter)
                          ├── 80%: HMAC-signed publish_success POST /webhooks  ──► main Lambda
                          └── 20%: fail → retry x3 → DLQ
                                    └── DLQ consumer → publish_failed webhook

Manual mock events
  └── POST mock-marketplace/listings/{id}/events
        ├── item_sold     → HMAC-signed POST /webhooks ──► main Lambda
        └── new_comment   → HMAC-signed POST /webhooks ──► main Lambda
```

| Service | Why |
|---|---|
| Lambda | Pay-per-request compute; no idle server or container cost |
| DynamoDB on-demand | $0 at zero traffic; no minimum charge unlike Aurora Serverless |
| SQS + DLQ | Managed retry counter; no counting code in Lambda; first 1 M requests free |
| API Gateway REST API | Simple Lambda proxy integration through CDK `LambdaRestApi`; sufficient for the prototype surface |
| S3 + CloudFront | Zero-server static hosting; near-zero cost |
| Secrets Manager | Webhook secret + mock API key; required by assignment |
| CDK TypeScript | Single `cdk deploy`; IaC in the same repo |

---

## Reference Marketplace — eBay

**Auth model:** OAuth 2.0. Client credentials for server-to-server calls; authorization-code flow for user-scoped actions. Access tokens expire in 2 hours and must be refreshed.

**Rate limits:** Inventory API allows ~5 000 calls/day per app by default; marketplaces with high traffic need a quota increase request. Bulk operations (bulkCreateOrReplaceInventoryItem) reduce call count.

**Webhook story:** eBay calls it *Platform Notifications*. You register an HTTPS endpoint in the developer portal and subscribe to topics (MARKETPLACE_ACCOUNT_DELETION, ITEM_SOLD, etc.). eBay signs payloads with an RSA key; the recipient verifies with eBay's public key fetched from a well-known URL.

**Known pitfalls:**
- eBay returns an external listing ID (`offerId`) that must be stored and mapped back to your internal `listingId` for all subsequent calls.
- Listing creation is asynchronous — a 202 response does not mean the listing is live; a separate status-check call or webhook is needed.
- Category and aspect (attribute) IDs are eBay-specific and must be resolved before publishing; wrong IDs silently fail.
- Webhook delivery is not guaranteed; a stuck-PENDING scanner is required in production.

*The real eBay integration is replaced with a mock marketplace in this prototype so the full async lifecycle can be demonstrated without external credentials.*

---

## Safety

**Credential storage:** Webhook HMAC secret and mock API key live in AWS Secrets Manager. Lambda reads them at cold-start; no secrets in code or environment variables.

**Multi-tenant isolation:** `sellerId` scopes all DynamoDB items. Currently hardcoded to `seller-001`; production would require Cognito (or equivalent) to bind an authenticated identity to every request.

**Idempotency of publish:** `listingId` (UUID, server-generated) is both the DynamoDB partition key and the idempotency key. `PUT item IF attribute_not_exists(listingId)` is a single atomic operation — no SELECT-then-INSERT race. Concurrent retries receive `ConditionalCheckFailedException` and return 409.

**Retry strategy:** SQS `maxReceiveCount=3` handles transient marketplace failures (network errors, 5xx). On exhaustion, the message moves to DLQ. `publish_failed` webhooks — meaning the marketplace accepted but rejected internally — are surfaced to the seller feed without auto-retry; the cause is likely a data error and retrying blindly risks rate-limit exhaustion.

**HMAC verification:** `MessageDigest.isEqual()` instead of `String.equals()` for constant-time comparison (timing-attack prevention).

---

## Cost

At **10 sellers / 1 k listings / 10 k events per month**:

| Service | Est. monthly cost |
|---|---|
| Lambda | ~$0 (within 1 M free-tier requests) |
| API Gateway REST API | ~$0.04 |
| DynamoDB on-demand | ~$0.03 |
| SQS | ~$0 (within 1 M free-tier requests) |
| S3 + CloudFront | ~$0.05 |
| Secrets Manager | ~$0.80 (2 secrets × $0.40) |
| **Total** | **~$1 / month** |

**First cost wall:** Secrets Manager dominates at this scale ($0.40/secret/month regardless of usage). The next wall is DynamoDB — on-demand read/write units stay cheap until millions of requests per month, at which point a provisioned-capacity baseline becomes cheaper. Lambda and SQS remain near-zero until hundreds of millions of invocations.

---

## What I Would Cut, What I Would Build Next

**Cut for this prototype (intentionally omitted):**
- Seller authentication (Cognito / JWT) — `sellerId` is hardcoded
- PENDING-stuck scanner — mock marketplace guarantees webhook delivery, so it is structurally impossible here
- Manual retry UI for `publish_failed`
- Outbox pattern for SQS — see known gap below

**Known gap — SQS publish after DynamoDB write:**
`listings` and `marketplace_listings` are written atomically via `TransactWriteItems`. SQS, however, is a separate system and cannot participate in a DynamoDB transaction. If the SQS send fails after a successful transaction write, the affected marketplace row stays stuck in `PENDING` indefinitely. The AWS SQS SDK retries internally, so this failure mode is extremely rare in practice; the risk is accepted and documented rather than solved.

The correct solution is the **Outbox pattern**: include a "pending publish" record inside the `TransactWriteItems` call, then have a separate Lambda (triggered by DynamoDB Streams or EventBridge Scheduler) read those records and send to SQS. This eliminates the gap at the cost of additional infrastructure (Streams consumer or Scheduler rule) and was out of scope for this prototype.

**Build next if this were a real product (priority order):**

1. **Seller auth (Cognito)** — every other feature depends on real identity; nothing else ships without this
2. **Real eBay OAuth + Inventory API adapter** — replace the mock; store `offerId` → `listingId` mapping
3. **PENDING-stuck scanner** — EventBridge Scheduler checks `marketplace_listings` rows stuck in PENDING beyond N minutes and marks them FAILED
4. **Outbox pattern for reliable SQS publish** — write publish intent inside the `TransactWriteItems` call; DynamoDB Streams consumer reads it and sends to SQS, closing the gap described above
5. **Manual retry for `publish_failed`** — one button in the UI; re-enqueues the SQS publish message
6. **Photo upload** — store listing photos in S3 and pass image references through the marketplace publish flow
7. **Facebook Marketplace adapter** — the factory pattern is in place; add a new `MarketplaceAdapter` implementation
