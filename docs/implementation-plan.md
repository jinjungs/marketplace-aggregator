# Implementation Plan

- [x] Done
- [~] In progress
- [ ] Not started

---

## Phase 1. CDK Infrastructure

- [x] 1-1. Initialize CDK project
- [x] 1-2. Define DynamoDB tables (listings, marketplace_listings, activity_events)
- [x] 1-3. Define SQS publish queue + DLQ
- [x] 1-4. Define Secrets Manager secrets (webhook-secret, mock-api-key)
- [x] 1-5. Define backend Lambda (Spring Boot + SnapStart)
- [x] 1-6. Define mock marketplace Lambda + SQS Delay Queue
- [x] 1-7. Define API Gateway (main)
- [x] 1-8. Define API Gateway (mock marketplace)
- [ ] 1-9. Define S3 bucket + CloudFront
- [ ] 1-10. IAM roles with least-privilege permissions

---

## Phase 2. Backend (Spring Boot)

- [x] 2-1. Initialize Spring Boot project (Java 21, aws-serverless-java-container)
- [x] 2-2. DynamoDB client configuration (AwsConfig, AppProperties)
- [x] 2-3. `POST /listings` — save listing + enqueue SQS message
- [x] 2-4. `GET /listings` — list listings + marketplace statuses
- [x] 2-4b. `GET /listings/{listingId}` — listing detail + full activity feed
- [x] 2-5. SQS Consumer — PublishConsumerHandler + PublishConsumerService
- [x] 2-6. MarketplaceAdapterFactory + EbayAdapter
- [x] 2-7. `POST /webhooks` — HMAC verification + save activity_events
- [x] 2-8. DynamoDB Conditional Write (idempotency) — included in POST /listings

---

## Phase 3. Mock Marketplace (Spring Boot Lambda)

- [x] 3-1. Initialize Spring Boot project
- [x] 3-2. `POST /mock/listings/publish` — 202 response + enqueue SQS Delay Queue
- [x] 3-3. SQS Consumer (Event Emitter) — 80/20 success/failure → `publish_success` webhook
- [x] 3-4. HMAC signing + webhook POST dispatch
- [x] 3-5. DLQ Consumer — send `publish_failed` webhook
- [x] 3-6. `POST /mock/listings/{listingId}/events` — manual trigger for `item_sold` / `new_comment`

---

## Phase 4. Frontend (Vanilla HTML/JS)

- [x] 4-1. Listing registration form (title, description, price, marketplace selection)
- [x] 4-2. Listing list + marketplace status badges
- [ ] 4-3. S3 upload + CloudFront serving

---

## Phase 5. Deploy & Test

- [~] 5-1. `cdk deploy` full stack — DynamoDB, SQS, Secrets, Lambda, API Gateway done. Mock Lambda, CloudFront remaining.
- [ ] 5-2. End-to-end flow test (listing → webhook → activity feed)
- [ ] 5-3. Verify 20% failure rate behavior
- [ ] 5-4. Verify DLQ behavior

---

## Phase 6. Documentation

- [ ] 6-1. Write README.md
- [ ] 6-2. Write APPROACH.md

---

## Known Limitations & Improvements

- **I-1. POST /listings transaction** — listings + marketplace_listings writes are not atomic. A mid-flight failure can leave orphaned data. Can be improved with DynamoDB TransactWriteItems. SQS is a separate system and cannot be included in the transaction.
- **I-2. PENDING stuck** — not implemented because the mock marketplace guarantees webhook delivery. For real marketplace integration, an EventBridge Scheduler timeout check would be required.
- **I-3. sellerId hardcoded** — currently fixed as `seller-001`. Requires real auth (Cognito, etc.) to extract from JWT.
- **I-4. GSI for externalListingId lookup** — not needed for mock (listingId passed directly in webhook body). For real eBay integration, a GSI on `externalListingId` in `marketplace_listings` would be required to resolve eBay's itemId back to our listingId.
- **I-5. N+1 on GET /listings** — each listing triggers separate DynamoDB queries for marketplace statuses and activity events. Acceptable at prototype scale.
