# Implementation Plan

## Status Legend
- [ ] Not started
- [~] In progress
- [x] Done

---

## Phase 1. CDK Infrastructure

| # | Task | Status | Notes |
|---|------|--------|-------|
| 1-1 | Initialize CDK project | [x] | |
| 1-2 | Define DynamoDB tables (listings, marketplace_listings, activity_events) | [x] | |
| 1-3 | Define SQS publish queue + DLQ | [x] | |
| 1-4 | Define Secrets Manager secrets (webhook-secret, mock-api-key) | [x] | |
| 1-5 | Define backend Lambda (Spring Boot + SnapStart) | [ ] | JAR 빌드 후 진행 |
| 1-6 | Define mock marketplace Lambda + SQS Delay Queue | [ ] | mock 모듈 완성 후 진행 |
| 1-7 | Define API Gateway (main) | [ ] | |
| 1-8 | Define API Gateway (mock marketplace) | [ ] | |
| 1-9 | Define S3 bucket + CloudFront | [ ] | |
| 1-10 | IAM roles with least-privilege permissions | [ ] | Lambda 정의 후 진행 |

---

## Phase 2. Backend (Spring Boot)

| # | Task | Status | Notes |
|---|------|--------|-------|
| 2-1 | Initialize Spring Boot project (Java 21, Lambda Web Adapter) | [x] | Lambda Web Adapter는 CDK에서 설정 |
| 2-2 | DynamoDB client configuration | [x] | |
| 2-3 | `POST /listings` — save listing + enqueue SQS message | [x] | |
| 2-4 | `GET /listings` — list listings + activity feed | [ ] | |
| 2-5 | SQS Consumer — invoke marketplace adapter | [ ] | |
| 2-6 | MarketplaceAdapterFactory + EbayAdapter | [ ] | |
| 2-7 | `POST /webhooks` — HMAC verification + save activity_events | [ ] | |
| 2-8 | DynamoDB Conditional Write (idempotency) | [x] | POST /listings에 포함됨 |

---

## Phase 3. Mock Marketplace (Spring Boot Lambda)

| # | Task | Status | Notes |
|---|------|--------|-------|
| 3-1 | Initialize Spring Boot project | [ ] | |
| 3-2 | `POST /mock/listings/publish` — 202 response + enqueue SQS Delay Queue | [ ] | |
| 3-3 | SQS Consumer (Event Emitter) — 80/20 success/failure | [ ] | |
| 3-4 | HMAC signing + webhook POST dispatch | [ ] | |
| 3-5 | DLQ Consumer — send publish_failed webhook | [ ] | |

---

## Phase 4. Frontend (Vanilla HTML/JS)

| # | Task | Status | Notes |
|---|------|--------|-------|
| 4-1 | Listing registration form (title, description, price, marketplace selection) | [ ] | |
| 4-2 | Listing list + activity feed view | [ ] | |
| 4-3 | S3 upload script | [ ] | |

---

## Phase 5. Deploy & Test

| # | Task | Status | Notes |
|---|------|--------|-------|
| 5-1 | `cdk deploy` full stack | [~] | DynamoDB, SQS, Secrets 배포 완료. Lambda, API GW, CloudFront 남음 |
| 5-2 | End-to-end flow test (listing → webhook → activity feed) | [ ] | |
| 5-3 | Verify 20% failure rate behavior | [ ] | |
| 5-4 | Verify DLQ behavior | [ ] | |

---

## Phase 6. Documentation

| # | Task | Status | Notes |
|---|------|--------|-------|
| 6-1 | Write README.md | [ ] | |
| 6-2 | Write APPROACH.md | [ ] | |
