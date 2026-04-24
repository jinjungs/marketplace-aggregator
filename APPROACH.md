# Marketplace Aggregator Approach

## Problem

Sellers often need to list the same product on multiple marketplaces and then watch each marketplace separately for publish status, sales, comments, and failures. This prototype centralizes the seller workflow:

1. Create one listing.
2. Publish it asynchronously to a marketplace.
3. Receive marketplace events through webhooks.
4. Show marketplace-specific status and activity in one backend API and simple frontend.

The reference marketplace is eBay, but the real eBay integration is replaced with a mock marketplace so the full asynchronous lifecycle can be demonstrated without external credentials.

## Architecture

The system is split into three deployable surfaces:

- Frontend: vanilla HTML/JS hosted from S3 through CloudFront.
- Main backend: Spring Boot on AWS Lambda behind API Gateway.
- Mock marketplace: separate Spring Boot Lambda and API Gateway, plus its own delay queue and DLQ.

Core AWS services:

- DynamoDB for listings, marketplace status, and activity events.
- SQS for async publish work and retry/DLQ handling.
- Secrets Manager for webhook HMAC secret and mock API key.
- CDK TypeScript for infrastructure.

The main flow is:

1. Browser calls `POST /listings`.
2. Backend writes `listings` and `marketplace_listings` rows.
3. Backend enqueues publish work to SQS.
4. Publish consumer Lambda calls the mock marketplace.
5. Mock marketplace returns `202 Accepted` and enqueues a delayed message.
6. Mock event emitter sends an HMAC-signed webhook.
7. Backend verifies the signature and records an activity event.
8. Backend updates marketplace status for `publish_success` or `publish_failed`.

## Data Model

The design separates product data from per-marketplace status.

`listings`

- Primary key: `listingId`
- Stores seller-owned product data such as title, description, price, timestamps.
- Does not contain a global status.

`marketplace_listings`

- Primary key: `listingId`
- Sort key: `marketplaceId`
- Stores marketplace-specific publish status: `PENDING`, `PUBLISHED`, `FAILED`.
- Allows one listing to succeed on eBay and fail on another marketplace independently.

`activity_events`

- Primary key: `listingId`
- Sort key: `timestamp#eventId`
- Stores append-only marketplace events such as `publish_success`, `publish_failed`, `item_sold`, and `new_comment`.

## Reliability Choices

Publishing is asynchronous. `POST /listings` returns after saving local state and enqueueing work; it does not wait for a marketplace response. This keeps seller-facing latency predictable and isolates marketplace failures from listing creation.

SQS provides retry behavior. The main publish queue has a DLQ. The mock marketplace delay queue also has a DLQ with `maxReceiveCount=3`. When the mock emitter fails repeatedly, the DLQ consumer sends a `publish_failed` webhook back to the backend.

Webhook authentication uses HMAC-SHA256. The mock marketplace signs the raw request body with a shared secret from Secrets Manager. The backend verifies the signature and uses `MessageDigest.isEqual()` for constant-time comparison.

Idempotency for listing creation is handled with `listingId` as the idempotency key and DynamoDB conditional writes.

## Marketplace Integration Boundary

The backend uses a `MarketplaceAdapter` interface and `MarketplaceAdapterFactory`. The current implementation has an `EbayAdapter` that calls the mock marketplace publish endpoint.

This keeps the marketplace-specific API call outside the listing service. A real eBay adapter could later handle OAuth, Inventory API calls, external listing IDs, and eBay-specific error mapping without changing the listing creation contract.

## Frontend

The frontend intentionally uses vanilla HTML/JS. It supports the assignment workflow without adding a build system:

- Create listing form
- Marketplace checkbox
- Listing list
- Marketplace status badges
- Refresh flow for asynchronous status changes

## Deployed Prototype

Current deployed endpoints:

- Frontend: https://dp3ng836djd04.cloudfront.net
- Backend API: https://7uhda0ji1b.execute-api.us-west-2.amazonaws.com/prod/
- Mock Marketplace API: https://niumrztk8b.execute-api.us-west-2.amazonaws.com/prod/

Verified flows:

- Listing creation through frontend/backend.
- Publish queue consumer calls mock marketplace.
- Mock marketplace sends `publish_success` webhook.
- Backend records activity and updates status to `PUBLISHED`.
- DLQ consumer sends `publish_failed` webhook.
- Backend records activity and updates status to `FAILED`.

## Tradeoffs And Limitations

The prototype keeps cost and scope low, so a few production concerns are intentionally left out:

- `sellerId` is hardcoded to `seller-001`; production needs authentication such as Cognito.
- `POST /listings` writes multiple DynamoDB rows and then SQS; this is not a single atomic transaction.
- There is no timeout scanner for stuck `PENDING` listings.
- The list endpoint uses simple per-listing follow-up queries, which is acceptable for prototype scale but not ideal for high-volume reads.
- Real eBay webhooks may require resolving external item IDs back to internal listing IDs, likely via a GSI on `externalListingId`.
- There is no manual retry UI for `publish_failed`.

## Why This Design

The goal is to show a realistic asynchronous marketplace integration without paying for always-on infrastructure. Lambda, DynamoDB on-demand, SQS, S3, and CloudFront are a good fit for a prototype because they are simple to operate and mostly usage-based.

The data model avoids a misleading global listing status. Status belongs to the relationship between a listing and a marketplace, not the listing itself. This is important once the system supports more than one marketplace.

The mock marketplace is separate from the backend so the system still exercises the real integration shape: outbound publish request, delayed marketplace processing, signed webhook callback, retry, and DLQ failure handling.
