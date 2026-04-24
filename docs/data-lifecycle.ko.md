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

### Step 2. Webhook 수신 — `item_sold` (eBay)

mock marketplace가 판매 완료 webhook을 발송한다.
webhook receiver가 `externalListingId`로 `listingId`를 조회한 뒤 두 테이블을 업데이트한다.

**listings** _(변경 없음)_

| listingId | sellerId | title | description | price | createdAt | updatedAt |
|-----------|----------|-------|-------------|-------|-----------|-----------|
| listing-001 | seller-001 | 맥북 프로 | 2023년형 | 1500000 | 2026-04-24T10:00:00Z | 2026-04-24T10:00:00Z |

**marketplace_listings** _(status, externalListingId, publishedAt, updatedAt 업데이트)_

| listingId | marketplaceId | status | externalListingId | publishedAt | failReason | createdAt | updatedAt |
|-----------|---------------|--------|-------------------|-------------|------------|-----------|-----------|
| listing-001 | ebay | PUBLISHED | ebay-item-12345 | 2026-04-24T10:05:00Z | null | 2026-04-24T10:00:00Z | 2026-04-24T10:05:00Z |

**activity_events** _(행 추가)_

| listingId | eventId | marketplaceId | timestamp | eventType | data |
|-----------|---------|---------------|-----------|-----------|------|
| listing-001 | evt-001 | ebay | 2026-04-24T10:05:00Z | item_sold | `{"buyerName":"홍길동","salePrice":1500000}` |

---

### Step 3. Webhook 수신 — `new_comment` (eBay)

구매자가 댓글을 달았을 때 발송된다. marketplace_listings 상태는 변하지 않는다.

**listings** _(변경 없음)_

| listingId | sellerId | title | description | price | createdAt | updatedAt |
|-----------|----------|-------|-------------|-------|-----------|-----------|
| listing-001 | seller-001 | 맥북 프로 | 2023년형 | 1500000 | 2026-04-24T10:00:00Z | 2026-04-24T10:00:00Z |

**marketplace_listings** _(변경 없음)_

| listingId | marketplaceId | status | externalListingId | publishedAt | failReason | createdAt | updatedAt |
|-----------|---------------|--------|-------------------|-------------|------------|-----------|-----------|
| listing-001 | ebay | PUBLISHED | ebay-item-12345 | 2026-04-24T10:05:00Z | null | 2026-04-24T10:00:00Z | 2026-04-24T10:05:00Z |

**activity_events** _(행 추가)_

| listingId | eventId | marketplaceId | timestamp | eventType | data |
|-----------|---------|---------------|-----------|-----------|------|
| listing-001 | evt-001 | ebay | 2026-04-24T10:05:00Z | item_sold | `{"buyerName":"홍길동","salePrice":1500000}` |
| listing-001 | evt-002 | ebay | 2026-04-24T10:10:00Z | new_comment | `{"comment":"아직 있나요?","buyerName":"김철수"}` |

---

### Step 4. Webhook 수신 — `publish_failed` (eBay)

발행 실패 시 발송된다. (Step 2, 3 대신 이 케이스가 오는 경우)

**listings** _(변경 없음)_

| listingId | sellerId | title | description | price | createdAt | updatedAt |
|-----------|----------|-------|-------------|-------|-----------|-----------|
| listing-001 | seller-001 | 맥북 프로 | 2023년형 | 1500000 | 2026-04-24T10:00:00Z | 2026-04-24T10:00:00Z |

**marketplace_listings** _(status, failReason, updatedAt 업데이트)_

| listingId | marketplaceId | status | externalListingId | publishedAt | failReason | createdAt | updatedAt |
|-----------|---------------|--------|-------------------|-------------|------------|-----------|-----------|
| listing-001 | ebay | FAILED | null | null | "Internal marketplace error" | 2026-04-24T10:00:00Z | 2026-04-24T10:05:00Z |

**activity_events** _(행 추가)_

| listingId | eventId | marketplaceId | timestamp | eventType | data |
|-----------|---------|---------------|-----------|-----------|------|
| listing-001 | evt-001 | ebay | 2026-04-24T10:05:00Z | publish_failed | `{"reason":"Internal marketplace error"}` |

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

### Step 2. Webhook 수신 — eBay `item_sold`, Facebook `publish_failed`

두 마켓플레이스에서 서로 다른 결과가 오는 경우. 각 행이 독립적으로 업데이트된다.

**listings** _(변경 없음)_

| listingId | sellerId | title | description | price | createdAt | updatedAt |
|-----------|----------|-------|-------------|-------|-----------|-----------|
| listing-002 | seller-001 | 아이패드 프로 | 2024년형 | 800000 | 2026-04-24T11:00:00Z | 2026-04-24T11:00:00Z |

**marketplace_listings** _(각 행 독립 업데이트)_

| listingId | marketplaceId | status | externalListingId | publishedAt | failReason | createdAt | updatedAt |
|-----------|---------------|--------|-------------------|-------------|------------|-----------|-----------|
| listing-002 | ebay | PUBLISHED | ebay-item-67890 | 2026-04-24T11:05:00Z | null | 2026-04-24T11:00:00Z | 2026-04-24T11:05:00Z |
| listing-002 | facebook | FAILED | null | null | "Category not supported" | 2026-04-24T11:00:00Z | 2026-04-24T11:06:00Z |

**activity_events**

| listingId | eventId | marketplaceId | timestamp | eventType | data |
|-----------|---------|---------------|-----------|-----------|------|
| listing-002 | evt-003 | ebay | 2026-04-24T11:05:00Z | item_sold | `{"buyerName":"이영희","salePrice":800000}` |
| listing-002 | evt-004 | facebook | 2026-04-24T11:06:00Z | publish_failed | `{"reason":"Category not supported"}` |

---

### Step 3. Webhook 수신 — Facebook `new_comment`

Facebook에서 추가로 댓글이 달린 경우. (Facebook이 FAILED임에도 댓글이 오는 건 현실에서는 없지만, new_comment가 독립적으로 처리됨을 보여주는 예시)

**listings** _(변경 없음)_

| listingId | sellerId | title | description | price | createdAt | updatedAt |
|-----------|----------|-------|-------------|-------|-----------|-----------|
| listing-002 | seller-001 | 아이패드 프로 | 2024년형 | 800000 | 2026-04-24T11:00:00Z | 2026-04-24T11:00:00Z |

**marketplace_listings** _(변경 없음)_

| listingId | marketplaceId | status | externalListingId | publishedAt | failReason | createdAt | updatedAt |
|-----------|---------------|--------|-------------------|-------------|------------|-----------|-----------|
| listing-002 | ebay | PUBLISHED | ebay-item-67890 | 2026-04-24T11:05:00Z | null | 2026-04-24T11:00:00Z | 2026-04-24T11:05:00Z |
| listing-002 | facebook | FAILED | null | null | "Category not supported" | 2026-04-24T11:00:00Z | 2026-04-24T11:06:00Z |

**activity_events** _(행 추가)_

| listingId | eventId | marketplaceId | timestamp | eventType | data |
|-----------|---------|---------------|-----------|-----------|------|
| listing-002 | evt-003 | ebay | 2026-04-24T11:05:00Z | item_sold | `{"buyerName":"이영희","salePrice":800000}` |
| listing-002 | evt-004 | facebook | 2026-04-24T11:06:00Z | publish_failed | `{"reason":"Category not supported"}` |
| listing-002 | evt-005 | facebook | 2026-04-24T11:15:00Z | new_comment | `{"comment":"언제 재등록 예정인가요?"}` |

---

## webhook 수신 시 내부 처리 흐름

```
webhook body: { "itemId": "ebay-item-12345", "event": "item_sold", ... }
        │
        ▼
HMAC 서명 검증 (실패 시 401)
        │
        ▼
marketplace_listings WHERE externalListingId = "ebay-item-12345"
        │
        ▼
listingId = "listing-001" 획득
        │
        ├── marketplace_listings 업데이트 (item_sold → status = PUBLISHED)
        │
        └── activity_events INSERT {
              listingId:     "listing-001",
              marketplaceId: "ebay",
              eventType:     "item_sold",
              data:          { 마켓플레이스에서 받은 값 그대로 }
            }
```

> `data` 필드는 마켓플레이스에서 받은 raw 값을 그대로 저장한다.
> 마켓플레이스마다 필드명이 다를 수 있고, 나중에 필드가 추가되더라도 스키마 변경 없이 저장 가능하다.
