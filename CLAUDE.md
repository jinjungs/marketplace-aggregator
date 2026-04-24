# CLAUDE.md

Project context and conventions for Claude Code.

---

## Project Overview

A unified marketplace aggregator that lets a seller register a product once and publish it to multiple marketplaces (eBay, Facebook Marketplace, etc.). All subsequent events — sales, comments, publish failures — flow back into a single activity feed.

This is a senior engineer assignment (Variant 2: Approach + Prototype). The goal is a working AWS-deployed prototype with a mocked marketplace integration.

---

## Tech Stack

| Layer | Choice |
|-------|--------|
| Backend | Java 21 + Spring Boot 3.x |
| Infrastructure | AWS CDK (TypeScript) |
| Compute | AWS Lambda + SnapStart |
| Database | DynamoDB (on-demand) |
| Queue | SQS + DLQ |
| Frontend | Vanilla HTML/JS (S3 + CloudFront) |
| Secrets | AWS Secrets Manager |

---

## Project Structure

```
marketplace-aggregator/
├── CLAUDE.md
├── APPROACH.md                  # Assignment write-up (to be written after implementation)
├── README.md                    # Deploy/teardown/cost guide (to be written after implementation)
├── cdk/                         # AWS infrastructure (TypeScript)
├── backend/                     # Spring Boot main app (Java 21)
├── mock-marketplace/            # Separate Lambda module (Java 21)
├── frontend/                    # Static HTML/JS
└── docs/
    ├── architecture.md          # English (default)
    ├── architecture.ko.md       # Korean
    ├── data-lifecycle.md        # English (default)
    ├── data-lifecycle.ko.md     # Korean
    ├── implementation-plan.md   # Task tracker
    └── csv/                     # Sample data
```

---

## Conventions

### File Naming
- English is the default. No suffix for English files.
- Korean variants use `.ko.md` suffix (e.g., `architecture.ko.md`)

### Commit Messages
Follow Conventional Commits:
```
<type>: <subject>

- bullet points for details
```

Types: `feat`, `fix`, `docs`, `refactor`, `test`, `chore`

### Documentation
- `docs/architecture.md` is the source of truth for architecture decisions.
- Use `/update-architecture` slash command to update both `architecture.md` and `architecture.ko.md` together.
- `docs/implementation-plan.md` tracks implementation progress. Update status as tasks complete.

---

## Key Design Decisions

- **URI**: `/listings` (marketplace industry standard term, not `/items` or `/products`)
- **DynamoDB**: `listings` table has no `status` field — status is per-marketplace in `marketplace_listings` table
- **Idempotency**: `listingId` (UUID) is the PK and serves as the idempotency key; DynamoDB conditional write (`attribute_not_exists`) prevents duplicates
- **Webhook receiver**: `/webhooks` single endpoint; event type in body; HMAC-SHA256 signature required
- **HMAC comparison**: Always use `MessageDigest.isEqual()`, never `String.equals()` (timing attack prevention)
- **Multiple marketplaces**: Factory pattern — `MarketplaceAdapterFactory` dispatches by `marketplaceId` in SQS message
- **publish_failed**: No auto-retry; surface to seller feed and await manual action
- **PENDING stuck**: Not implemented — mock marketplace guarantees webhook delivery; would need EventBridge Scheduler timeout check for real integration
- **SQS retry count**: Managed by SQS `maxReceiveCount` config, not in Lambda code
- **Frontend**: Vanilla HTML/JS only — no React, no build tools

---

## What NOT To Do

- Do not commit AWS credentials, secrets, or `.env` files
- Do not use `String.equals()` for HMAC signature comparison
- Do not add `status` field to the `listings` table
- Do not auto-retry `publish_failed` events
- Do not use provisioned capacity on DynamoDB or Aurora (cost)
- Do not add NAT gateways or always-on ECS services (cost)
- Do not add features beyond the assignment scope
