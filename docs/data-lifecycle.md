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

### Step 2. Webhook received — `item_sold` (eBay)

The mock marketplace sends an item sold webhook.
The webhook receiver looks up `listingId` by `externalListingId`, then updates both tables.

**listings** _(no change)_

| listingId | sellerId | title | description | price | createdAt | updatedAt |
|-----------|----------|-------|-------------|-------|-----------|-----------|
| listing-001 | seller-001 | MacBook Pro | 2023 model | 1500000 | 2026-04-24T10:00:00Z | 2026-04-24T10:00:00Z |

**marketplace_listings** _(status, externalListingId, publishedAt, updatedAt updated)_

| listingId | marketplaceId | status | externalListingId | publishedAt | failReason | createdAt | updatedAt |
|-----------|---------------|--------|-------------------|-------------|------------|-----------|-----------|
| listing-001 | ebay | PUBLISHED | ebay-item-12345 | 2026-04-24T10:05:00Z | null | 2026-04-24T10:00:00Z | 2026-04-24T10:05:00Z |

**activity_events** _(row added)_

| listingId | eventId | marketplaceId | timestamp | eventType | data |
|-----------|---------|---------------|-----------|-----------|------|
| listing-001 | evt-001 | ebay | 2026-04-24T10:05:00Z | item_sold | `{"buyerName":"John Doe","salePrice":1500000}` |

---

### Step 3. Webhook received — `new_comment` (eBay)

Sent when a buyer leaves a comment. marketplace_listings status does not change.

**listings** _(no change)_

| listingId | sellerId | title | description | price | createdAt | updatedAt |
|-----------|----------|-------|-------------|-------|-----------|-----------|
| listing-001 | seller-001 | MacBook Pro | 2023 model | 1500000 | 2026-04-24T10:00:00Z | 2026-04-24T10:00:00Z |

**marketplace_listings** _(no change)_

| listingId | marketplaceId | status | externalListingId | publishedAt | failReason | createdAt | updatedAt |
|-----------|---------------|--------|-------------------|-------------|------------|-----------|-----------|
| listing-001 | ebay | PUBLISHED | ebay-item-12345 | 2026-04-24T10:05:00Z | null | 2026-04-24T10:00:00Z | 2026-04-24T10:05:00Z |

**activity_events** _(row added)_

| listingId | eventId | marketplaceId | timestamp | eventType | data |
|-----------|---------|---------------|-----------|-----------|------|
| listing-001 | evt-001 | ebay | 2026-04-24T10:05:00Z | item_sold | `{"buyerName":"John Doe","salePrice":1500000}` |
| listing-001 | evt-002 | ebay | 2026-04-24T10:10:00Z | new_comment | `{"comment":"Is this still available?","buyerName":"Jane Smith"}` |

---

### Step 4. Webhook received — `publish_failed` (eBay)

Sent when publishing fails. (This replaces Steps 2 and 3 in the failure case.)

**listings** _(no change)_

| listingId | sellerId | title | description | price | createdAt | updatedAt |
|-----------|----------|-------|-------------|-------|-----------|-----------|
| listing-001 | seller-001 | MacBook Pro | 2023 model | 1500000 | 2026-04-24T10:00:00Z | 2026-04-24T10:00:00Z |

**marketplace_listings** _(status, failReason, updatedAt updated)_

| listingId | marketplaceId | status | externalListingId | publishedAt | failReason | createdAt | updatedAt |
|-----------|---------------|--------|-------------------|-------------|------------|-----------|-----------|
| listing-001 | ebay | FAILED | null | null | "Internal marketplace error" | 2026-04-24T10:00:00Z | 2026-04-24T10:05:00Z |

**activity_events** _(row added)_

| listingId | eventId | marketplaceId | timestamp | eventType | data |
|-----------|---------|---------------|-----------|-----------|------|
| listing-001 | evt-001 | ebay | 2026-04-24T10:05:00Z | publish_failed | `{"reason":"Internal marketplace error"}` |

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

### Step 2. Webhooks received — eBay `item_sold`, Facebook `publish_failed`

Different results arrive from each marketplace. Each row is updated independently.

**listings** _(no change)_

| listingId | sellerId | title | description | price | createdAt | updatedAt |
|-----------|----------|-------|-------------|-------|-----------|-----------|
| listing-002 | seller-001 | iPad Pro | 2024 model | 800000 | 2026-04-24T11:00:00Z | 2026-04-24T11:00:00Z |

**marketplace_listings** _(each row updated independently)_

| listingId | marketplaceId | status | externalListingId | publishedAt | failReason | createdAt | updatedAt |
|-----------|---------------|--------|-------------------|-------------|------------|-----------|-----------|
| listing-002 | ebay | PUBLISHED | ebay-item-67890 | 2026-04-24T11:05:00Z | null | 2026-04-24T11:00:00Z | 2026-04-24T11:05:00Z |
| listing-002 | facebook | FAILED | null | null | "Category not supported" | 2026-04-24T11:00:00Z | 2026-04-24T11:06:00Z |

**activity_events**

| listingId | eventId | marketplaceId | timestamp | eventType | data |
|-----------|---------|---------------|-----------|-----------|------|
| listing-002 | evt-003 | ebay | 2026-04-24T11:05:00Z | item_sold | `{"buyerName":"Jane Smith","salePrice":800000}` |
| listing-002 | evt-004 | facebook | 2026-04-24T11:06:00Z | publish_failed | `{"reason":"Category not supported"}` |

---

### Step 3. Webhook received — Facebook `new_comment`

An additional comment arrives from Facebook. (Shown here to illustrate that new_comment is handled independently of publish status.)

**listings** _(no change)_

| listingId | sellerId | title | description | price | createdAt | updatedAt |
|-----------|----------|-------|-------------|-------|-----------|-----------|
| listing-002 | seller-001 | iPad Pro | 2024 model | 800000 | 2026-04-24T11:00:00Z | 2026-04-24T11:00:00Z |

**marketplace_listings** _(no change)_

| listingId | marketplaceId | status | externalListingId | publishedAt | failReason | createdAt | updatedAt |
|-----------|---------------|--------|-------------------|-------------|------------|-----------|-----------|
| listing-002 | ebay | PUBLISHED | ebay-item-67890 | 2026-04-24T11:05:00Z | null | 2026-04-24T11:00:00Z | 2026-04-24T11:05:00Z |
| listing-002 | facebook | FAILED | null | null | "Category not supported" | 2026-04-24T11:00:00Z | 2026-04-24T11:06:00Z |

**activity_events** _(row added)_

| listingId | eventId | marketplaceId | timestamp | eventType | data |
|-----------|---------|---------------|-----------|-----------|------|
| listing-002 | evt-003 | ebay | 2026-04-24T11:05:00Z | item_sold | `{"buyerName":"Jane Smith","salePrice":800000}` |
| listing-002 | evt-004 | facebook | 2026-04-24T11:06:00Z | publish_failed | `{"reason":"Category not supported"}` |
| listing-002 | evt-005 | facebook | 2026-04-24T11:15:00Z | new_comment | `{"comment":"When will this be relisted?"}` |

---

## Internal processing flow on webhook receipt

```
webhook body: { "itemId": "ebay-item-12345", "event": "item_sold", ... }
        │
        ▼
Verify HMAC signature (401 if invalid)
        │
        ▼
marketplace_listings WHERE externalListingId = "ebay-item-12345"
        │
        ▼
Resolve listingId = "listing-001"
        │
        ├── Update marketplace_listings (item_sold → status = PUBLISHED)
        │
        └── INSERT activity_events {
              listingId:     "listing-001",
              marketplaceId: "ebay",
              eventType:     "item_sold",
              data:          { raw values from marketplace webhook }
            }
```

> The `data` field stores raw values received from the marketplace as-is.
> Field names vary by marketplace, and new fields can be added without schema changes.
