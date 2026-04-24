# Data Lifecycle

Defines how the three tables (`listings`, `marketplace_listings`, `activity_events`) change for each event.

---

## Scenario 1 — Listed on eBay only

### Step 1. `POST /listings` (eBay selected)

A listings row is created and a PENDING row appears in marketplace_listings.
A publish request is sent asynchronously to the mock marketplace via SQS.

**listings**

| listingId | sellerId | title | description | price | createdAt | updatedAt |
|-----------|----------|-------|-------------|-------|-----------|-----------|
| listing-001 | seller-001 | MacBook Pro | 2023 model | 1500000 | 2026-04-24T10:00:00Z | 2026-04-24T10:00:00Z |

**marketplace_listings**

| listingId | marketplaceId | status | externalListingId | publishedAt | failReason | createdAt | updatedAt |
|-----------|---------------|--------|-------------------|-------------|------------|-----------|-----------|
| listing-001 | ebay | PENDING | null | null | null | 2026-04-24T10:00:00Z | 2026-04-24T10:00:00Z |

**activity_events**

| listingId | eventId | marketplaceId | timestamp | eventType | data |
|-----------|---------|---------------|-----------|-----------|------|
| _(empty)_ | | | | | |

---

### Step 2. Webhook received — `publish_success` (eBay)

The mock marketplace sends a publish success webhook (80% probability).
status transitions PENDING → PUBLISHED; externalListingId and publishedAt are set.

**listings** _(no change)_

| listingId | sellerId | title | description | price | createdAt | updatedAt |
|-----------|----------|-------|-------------|-------|-----------|-----------|
| listing-001 | seller-001 | MacBook Pro | 2023 model | 1500000 | 2026-04-24T10:00:00Z | 2026-04-24T10:00:00Z |

**marketplace_listings** _(status, externalListingId, publishedAt, updatedAt updated)_

| listingId | marketplaceId | status | externalListingId | publishedAt | failReason | createdAt | updatedAt |
|-----------|---------------|--------|-------------------|-------------|------------|-----------|-----------|
| listing-001 | ebay | PUBLISHED | mock-a1b2c3d4 | 2026-04-24T10:05:00Z | null | 2026-04-24T10:00:00Z | 2026-04-24T10:05:00Z |

**activity_events** _(row added)_

| listingId | eventId | marketplaceId | timestamp | eventType | data |
|-----------|---------|---------------|-----------|-----------|------|
| listing-001 | evt-001 | ebay | 2026-04-24T10:05:00Z | publish_success | `{"externalListingId":"mock-a1b2c3d4"}` |

---

### Step 3. Webhook received — `item_sold` (eBay)

Triggered manually via `POST /mock/listings/{listingId}/events` with `{"eventType":"item_sold"}`.
marketplace_listings status does not change (already PUBLISHED).

**listings** _(no change)_

| listingId | sellerId | title | description | price | createdAt | updatedAt |
|-----------|----------|-------|-------------|-------|-----------|-----------|
| listing-001 | seller-001 | MacBook Pro | 2023 model | 1500000 | 2026-04-24T10:00:00Z | 2026-04-24T10:00:00Z |

**marketplace_listings** _(no change)_

| listingId | marketplaceId | status | externalListingId | publishedAt | failReason | createdAt | updatedAt |
|-----------|---------------|--------|-------------------|-------------|------------|-----------|-----------|
| listing-001 | ebay | PUBLISHED | mock-a1b2c3d4 | 2026-04-24T10:05:00Z | null | 2026-04-24T10:00:00Z | 2026-04-24T10:05:00Z |

**activity_events** _(row added)_

| listingId | eventId | marketplaceId | timestamp | eventType | data |
|-----------|---------|---------------|-----------|-----------|------|
| listing-001 | evt-001 | ebay | 2026-04-24T10:05:00Z | publish_success | `{"externalListingId":"mock-a1b2c3d4"}` |
| listing-001 | evt-002 | ebay | 2026-04-24T10:10:00Z | item_sold | `{"buyerName":"Test Buyer","salePrice":1500000,"transactionId":"txn-001"}` |

---

### Step 4. Webhook received — `new_comment` (eBay)

Triggered manually via `POST /mock/listings/{listingId}/events` with `{"eventType":"new_comment"}`.
marketplace_listings status does not change.

**listings** _(no change)_

| listingId | sellerId | title | description | price | createdAt | updatedAt |
|-----------|----------|-------|-------------|-------|-----------|-----------|
| listing-001 | seller-001 | MacBook Pro | 2023 model | 1500000 | 2026-04-24T10:00:00Z | 2026-04-24T10:00:00Z |

**marketplace_listings** _(no change)_

| listingId | marketplaceId | status | externalListingId | publishedAt | failReason | createdAt | updatedAt |
|-----------|---------------|--------|-------------------|-------------|------------|-----------|-----------|
| listing-001 | ebay | PUBLISHED | mock-a1b2c3d4 | 2026-04-24T10:05:00Z | null | 2026-04-24T10:00:00Z | 2026-04-24T10:05:00Z |

**activity_events** _(row added)_

| listingId | eventId | marketplaceId | timestamp | eventType | data |
|-----------|---------|---------------|-----------|-----------|------|
| listing-001 | evt-001 | ebay | 2026-04-24T10:05:00Z | publish_success | `{"externalListingId":"mock-a1b2c3d4"}` |
| listing-001 | evt-002 | ebay | 2026-04-24T10:10:00Z | item_sold | `{"buyerName":"Test Buyer","salePrice":1500000,"transactionId":"txn-001"}` |
| listing-001 | evt-003 | ebay | 2026-04-24T10:15:00Z | new_comment | `{"comment":"Is this still available?","buyerName":"Test Buyer"}` |

---

### Step 2 (failure path). Webhook received — `publish_failed` (eBay)

The mock marketplace sends a publish_failed webhook (20% probability, after SQS maxReceiveCount exhausted via DLQ).
status transitions PENDING → FAILED.

**listings** _(no change)_

| listingId | sellerId | title | description | price | createdAt | updatedAt |
|-----------|----------|-------|-------------|-------|-----------|-----------|
| listing-001 | seller-001 | MacBook Pro | 2023 model | 1500000 | 2026-04-24T10:00:00Z | 2026-04-24T10:00:00Z |

**marketplace_listings** _(status, updatedAt updated)_

| listingId | marketplaceId | status | externalListingId | publishedAt | failReason | createdAt | updatedAt |
|-----------|---------------|--------|-------------------|-------------|------------|-----------|-----------|
| listing-001 | ebay | FAILED | null | null | null | 2026-04-24T10:00:00Z | 2026-04-24T10:05:00Z |

**activity_events** _(row added)_

| listingId | eventId | marketplaceId | timestamp | eventType | data |
|-----------|---------|---------------|-----------|-----------|------|
| listing-001 | evt-001 | ebay | 2026-04-24T10:05:00Z | publish_failed | `{"reason":"Simulated marketplace error"}` |

---

## Scenario 2 — Listed on both eBay and Facebook Marketplace

### Step 1. `POST /listings` (eBay + Facebook selected)

Two PENDING rows are created in marketplace_listings. Two messages are enqueued in SQS.

**listings**

| listingId | sellerId | title | description | price | createdAt | updatedAt |
|-----------|----------|-------|-------------|-------|-----------|-----------|
| listing-002 | seller-001 | iPad Pro | 2024 model | 800000 | 2026-04-24T11:00:00Z | 2026-04-24T11:00:00Z |

**marketplace_listings**

| listingId | marketplaceId | status | externalListingId | publishedAt | failReason | createdAt | updatedAt |
|-----------|---------------|--------|-------------------|-------------|------------|-----------|-----------|
| listing-002 | ebay | PENDING | null | null | null | 2026-04-24T11:00:00Z | 2026-04-24T11:00:00Z |
| listing-002 | facebook | PENDING | null | null | null | 2026-04-24T11:00:00Z | 2026-04-24T11:00:00Z |

**activity_events**

| listingId | eventId | marketplaceId | timestamp | eventType | data |
|-----------|---------|---------------|-----------|-----------|------|
| _(empty)_ | | | | | |

---

### Step 2. Webhooks received — eBay `publish_success`, Facebook `publish_failed`

Different results arrive from each marketplace. Each row is updated independently.

**listings** _(no change)_

| listingId | sellerId | title | description | price | createdAt | updatedAt |
|-----------|----------|-------|-------------|-------|-----------|-----------|
| listing-002 | seller-001 | iPad Pro | 2024 model | 800000 | 2026-04-24T11:00:00Z | 2026-04-24T11:00:00Z |

**marketplace_listings** _(each row updated independently)_

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

### Step 3. Webhook received — eBay `item_sold`

Triggered manually. marketplace_listings status does not change.

**listings** _(no change)_

| listingId | sellerId | title | description | price | createdAt | updatedAt |
|-----------|----------|-------|-------------|-------|-----------|-----------|
| listing-002 | seller-001 | iPad Pro | 2024 model | 800000 | 2026-04-24T11:00:00Z | 2026-04-24T11:00:00Z |

**marketplace_listings** _(no change)_

| listingId | marketplaceId | status | externalListingId | publishedAt | failReason | createdAt | updatedAt |
|-----------|---------------|--------|-------------------|-------------|------------|-----------|-----------|
| listing-002 | ebay | PUBLISHED | mock-e5f6g7h8 | 2026-04-24T11:05:00Z | null | 2026-04-24T11:00:00Z | 2026-04-24T11:05:00Z |
| listing-002 | facebook | FAILED | null | null | null | 2026-04-24T11:00:00Z | 2026-04-24T11:06:00Z |

**activity_events** _(row added)_

| listingId | eventId | marketplaceId | timestamp | eventType | data |
|-----------|---------|---------------|-----------|-----------|------|
| listing-002 | evt-003 | ebay | 2026-04-24T11:05:00Z | publish_success | `{"externalListingId":"mock-e5f6g7h8"}` |
| listing-002 | evt-004 | facebook | 2026-04-24T11:06:00Z | publish_failed | `{"reason":"Simulated marketplace error"}` |
| listing-002 | evt-005 | ebay | 2026-04-24T11:15:00Z | item_sold | `{"buyerName":"Test Buyer","salePrice":800000,"transactionId":"txn-002"}` |

---

## Internal processing flow on webhook receipt

```
POST /webhooks
body: { "event": "publish_success", "listingId": "listing-001", "marketplaceId": "ebay", "data": {...} }
        │
        ▼
Verify HMAC signature (401 if invalid)
        │
        ▼
INSERT activity_events {
  listingId, marketplaceId, eventType, timestamp, data
}
        │
        ▼
switch(event)
  publish_success → UPDATE marketplace_listings
                    SET status=PUBLISHED, externalListingId, publishedAt, updatedAt
  publish_failed  → UPDATE marketplace_listings
                    SET status=FAILED, updatedAt
  item_sold       → (no status update)
  new_comment     → (no status update)
```

> `listingId` is passed directly in the webhook body — no GSI lookup required.
> `data` stores raw values from the marketplace as-is; field names vary per marketplace.
