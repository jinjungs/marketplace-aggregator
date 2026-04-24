# 데이터 생성 규칙 (Data Lifecycle)

각 이벤트 발생 시 세 개의 테이블(`listings`, `marketplace_listings`, `activity_events`)이 어떻게 변하는지 정의한다.

---

## 시나리오 1 — eBay에만 등록

### Step 1. `POST /listings` (eBay 선택)

판매자가 상품을 등록하면 listings 행이 생성되고, marketplace_listings에 PENDING 상태가 생긴다.
SQS를 통해 비동기로 mock marketplace에 발행 요청이 전송된다.

**listings**

| listingId | sellerId | title | description | price | createdAt | updatedAt |
|-----------|----------|-------|-------------|-------|-----------|-----------|
| listing-001 | seller-001 | 맥북 프로 | 2023년형 | 1500000 | 2026-04-24T10:00:00Z | 2026-04-24T10:00:00Z |

**marketplace_listings**

| listingId | marketplaceId | status | externalListingId | publishedAt | failReason | createdAt | updatedAt |
|-----------|---------------|--------|-------------------|-------------|------------|-----------|-----------|
| listing-001 | ebay | PENDING | null | null | null | 2026-04-24T10:00:00Z | 2026-04-24T10:00:00Z |

**activity_events**

| listingId | eventId | marketplaceId | timestamp | eventType | data |
|-----------|---------|---------------|-----------|-----------|------|
| _(비어있음)_ | | | | | |

---

### Step 2. Webhook 수신 — `publish_success` (eBay)

mock marketplace가 발행 성공 webhook을 발송한다 (80% 확률).
status가 PENDING → PUBLISHED로 전환되고, externalListingId와 publishedAt이 설정된다.

**listings** _(변경 없음)_

| listingId | sellerId | title | description | price | createdAt | updatedAt |
|-----------|----------|-------|-------------|-------|-----------|-----------|
| listing-001 | seller-001 | 맥북 프로 | 2023년형 | 1500000 | 2026-04-24T10:00:00Z | 2026-04-24T10:00:00Z |

**marketplace_listings** _(status, externalListingId, publishedAt, updatedAt 업데이트)_

| listingId | marketplaceId | status | externalListingId | publishedAt | failReason | createdAt | updatedAt |
|-----------|---------------|--------|-------------------|-------------|------------|-----------|-----------|
| listing-001 | ebay | PUBLISHED | mock-a1b2c3d4 | 2026-04-24T10:05:00Z | null | 2026-04-24T10:00:00Z | 2026-04-24T10:05:00Z |

**activity_events** _(행 추가)_

| listingId | eventId | marketplaceId | timestamp | eventType | data |
|-----------|---------|---------------|-----------|-----------|------|
| listing-001 | evt-001 | ebay | 2026-04-24T10:05:00Z | publish_success | `{"externalListingId":"mock-a1b2c3d4"}` |

---

### Step 3. Webhook 수신 — `item_sold` (eBay)

`POST /mock/listings/{listingId}/events` + `{"eventType":"item_sold"}`로 수동 트리거한다.
marketplace_listings status는 변하지 않는다 (이미 PUBLISHED).

**listings** _(변경 없음)_

| listingId | sellerId | title | description | price | createdAt | updatedAt |
|-----------|----------|-------|-------------|-------|-----------|-----------|
| listing-001 | seller-001 | 맥북 프로 | 2023년형 | 1500000 | 2026-04-24T10:00:00Z | 2026-04-24T10:00:00Z |

**marketplace_listings** _(변경 없음)_

| listingId | marketplaceId | status | externalListingId | publishedAt | failReason | createdAt | updatedAt |
|-----------|---------------|--------|-------------------|-------------|------------|-----------|-----------|
| listing-001 | ebay | PUBLISHED | mock-a1b2c3d4 | 2026-04-24T10:05:00Z | null | 2026-04-24T10:00:00Z | 2026-04-24T10:05:00Z |

**activity_events** _(행 추가)_

| listingId | eventId | marketplaceId | timestamp | eventType | data |
|-----------|---------|---------------|-----------|-----------|------|
| listing-001 | evt-001 | ebay | 2026-04-24T10:05:00Z | publish_success | `{"externalListingId":"mock-a1b2c3d4"}` |
| listing-001 | evt-002 | ebay | 2026-04-24T10:10:00Z | item_sold | `{"buyerName":"Test Buyer","salePrice":1500000,"transactionId":"txn-001"}` |

---

### Step 4. Webhook 수신 — `new_comment` (eBay)

`POST /mock/listings/{listingId}/events` + `{"eventType":"new_comment"}`로 수동 트리거한다.
marketplace_listings status는 변하지 않는다.

**listings** _(변경 없음)_

| listingId | sellerId | title | description | price | createdAt | updatedAt |
|-----------|----------|-------|-------------|-------|-----------|-----------|
| listing-001 | seller-001 | 맥북 프로 | 2023년형 | 1500000 | 2026-04-24T10:00:00Z | 2026-04-24T10:00:00Z |

**marketplace_listings** _(변경 없음)_

| listingId | marketplaceId | status | externalListingId | publishedAt | failReason | createdAt | updatedAt |
|-----------|---------------|--------|-------------------|-------------|------------|-----------|-----------|
| listing-001 | ebay | PUBLISHED | mock-a1b2c3d4 | 2026-04-24T10:05:00Z | null | 2026-04-24T10:00:00Z | 2026-04-24T10:05:00Z |

**activity_events** _(행 추가)_

| listingId | eventId | marketplaceId | timestamp | eventType | data |
|-----------|---------|---------------|-----------|-----------|------|
| listing-001 | evt-001 | ebay | 2026-04-24T10:05:00Z | publish_success | `{"externalListingId":"mock-a1b2c3d4"}` |
| listing-001 | evt-002 | ebay | 2026-04-24T10:10:00Z | item_sold | `{"buyerName":"Test Buyer","salePrice":1500000,"transactionId":"txn-001"}` |
| listing-001 | evt-003 | ebay | 2026-04-24T10:15:00Z | new_comment | `{"comment":"아직 있나요?","buyerName":"Test Buyer"}` |

---

### Step 2 (실패 경로). Webhook 수신 — `publish_failed` (eBay)

mock marketplace가 발행 실패 webhook을 발송한다 (20% 확률, SQS maxReceiveCount 소진 후 DLQ 경유).
status가 PENDING → FAILED로 전환된다.

**listings** _(변경 없음)_

| listingId | sellerId | title | description | price | createdAt | updatedAt |
|-----------|----------|-------|-------------|-------|-----------|-----------|
| listing-001 | seller-001 | 맥북 프로 | 2023년형 | 1500000 | 2026-04-24T10:00:00Z | 2026-04-24T10:00:00Z |

**marketplace_listings** _(status, updatedAt 업데이트)_

| listingId | marketplaceId | status | externalListingId | publishedAt | failReason | createdAt | updatedAt |
|-----------|---------------|--------|-------------------|-------------|------------|-----------|-----------|
| listing-001 | ebay | FAILED | null | null | null | 2026-04-24T10:00:00Z | 2026-04-24T10:05:00Z |

**activity_events** _(행 추가)_

| listingId | eventId | marketplaceId | timestamp | eventType | data |
|-----------|---------|---------------|-----------|-----------|------|
| listing-001 | evt-001 | ebay | 2026-04-24T10:05:00Z | publish_failed | `{"reason":"Simulated marketplace error"}` |

---

## 시나리오 2 — eBay + Facebook Marketplace에 동시 등록

### Step 1. `POST /listings` (eBay + Facebook 선택)

marketplace_listings에 두 개의 PENDING 행이 생긴다. SQS에는 두 개의 메시지가 적재된다.

**listings**

| listingId | sellerId | title | description | price | createdAt | updatedAt |
|-----------|----------|-------|-------------|-------|-----------|-----------|
| listing-002 | seller-001 | 아이패드 프로 | 2024년형 | 800000 | 2026-04-24T11:00:00Z | 2026-04-24T11:00:00Z |

**marketplace_listings**

| listingId | marketplaceId | status | externalListingId | publishedAt | failReason | createdAt | updatedAt |
|-----------|---------------|--------|-------------------|-------------|------------|-----------|-----------|
| listing-002 | ebay | PENDING | null | null | null | 2026-04-24T11:00:00Z | 2026-04-24T11:00:00Z |
| listing-002 | facebook | PENDING | null | null | null | 2026-04-24T11:00:00Z | 2026-04-24T11:00:00Z |

**activity_events**

| listingId | eventId | marketplaceId | timestamp | eventType | data |
|-----------|---------|---------------|-----------|-----------|------|
| _(비어있음)_ | | | | | |

---

### Step 2. Webhook 수신 — eBay `publish_success`, Facebook `publish_failed`

두 마켓플레이스에서 서로 다른 결과가 오는 경우. 각 행이 독립적으로 업데이트된다.

**listings** _(변경 없음)_

| listingId | sellerId | title | description | price | createdAt | updatedAt |
|-----------|----------|-------|-------------|-------|-----------|-----------|
| listing-002 | seller-001 | 아이패드 프로 | 2024년형 | 800000 | 2026-04-24T11:00:00Z | 2026-04-24T11:00:00Z |

**marketplace_listings** _(각 행 독립 업데이트)_

| listingId | marketplaceId | status | externalListingId | publishedAt | failReason | createdAt | updatedAt |
|-----------|---------------|--------|-------------------|-------------|------------|-----------|-----------|
| listing-002 | ebay | PUBLISHED | mock-e5f6g7h8 | 2026-04-24T11:05:00Z | null | 2026-04-24T11:00:00Z | 2026-04-24T11:05:00Z |
| listing-002 | facebook | FAILED | null | null | null | 2026-04-24T11:00:00Z | 2026-04-24T11:06:00Z |

**activity_events**

| listingId | eventId | marketplaceId | timestamp | eventType | data |
|-----------|---------|---------------|-----------|-----------|------|
| listing-002 | evt-003 | ebay | 2026-04-24T11:05:00Z | publish_success | `{"externalListingId":"mock-e5f6g7h8"}` |
| listing-002 | evt-004 | facebook | 2026-04-24T11:06:00Z | publish_failed | `{"reason":"Simulated marketplace error"}` |

---

### Step 3. Webhook 수신 — eBay `item_sold`

수동 트리거. marketplace_listings status는 변하지 않는다.

**listings** _(변경 없음)_

| listingId | sellerId | title | description | price | createdAt | updatedAt |
|-----------|----------|-------|-------------|-------|-----------|-----------|
| listing-002 | seller-001 | 아이패드 프로 | 2024년형 | 800000 | 2026-04-24T11:00:00Z | 2026-04-24T11:00:00Z |

**marketplace_listings** _(변경 없음)_

| listingId | marketplaceId | status | externalListingId | publishedAt | failReason | createdAt | updatedAt |
|-----------|---------------|--------|-------------------|-------------|------------|-----------|-----------|
| listing-002 | ebay | PUBLISHED | mock-e5f6g7h8 | 2026-04-24T11:05:00Z | null | 2026-04-24T11:00:00Z | 2026-04-24T11:05:00Z |
| listing-002 | facebook | FAILED | null | null | null | 2026-04-24T11:00:00Z | 2026-04-24T11:06:00Z |

**activity_events** _(행 추가)_

| listingId | eventId | marketplaceId | timestamp | eventType | data |
|-----------|---------|---------------|-----------|-----------|------|
| listing-002 | evt-003 | ebay | 2026-04-24T11:05:00Z | publish_success | `{"externalListingId":"mock-e5f6g7h8"}` |
| listing-002 | evt-004 | facebook | 2026-04-24T11:06:00Z | publish_failed | `{"reason":"Simulated marketplace error"}` |
| listing-002 | evt-005 | ebay | 2026-04-24T11:15:00Z | item_sold | `{"buyerName":"Test Buyer","salePrice":800000,"transactionId":"txn-002"}` |

---

## webhook 수신 시 내부 처리 흐름

```
POST /webhooks
body: { "event": "publish_success", "listingId": "listing-001", "marketplaceId": "ebay", "data": {...} }
        │
        ▼
HMAC 서명 검증 (실패 시 401)
        │
        ▼
activity_events INSERT {
  listingId, marketplaceId, eventType, timestamp, data
}
        │
        ▼
switch(event)
  publish_success → marketplace_listings UPDATE
                    SET status=PUBLISHED, externalListingId, publishedAt, updatedAt
  publish_failed  → marketplace_listings UPDATE
                    SET status=FAILED, updatedAt
  item_sold       → (status 변경 없음)
  new_comment     → (status 변경 없음)
```

> `listingId`는 webhook body에 직접 포함되어 있어 GSI 조회가 필요 없다.
> `data` 필드는 마켓플레이스에서 받은 raw 값을 그대로 저장한다. 마켓플레이스마다 필드명이 다를 수 있고, 나중에 필드가 추가되더라도 스키마 변경 없이 저장 가능하다.
