# Marketplace Aggregator

A serverless prototype for publishing a seller's listing to a mocked marketplace and collecting marketplace events in one activity feed.

## Deployed URLs

| Name | URL |
|------|-----|
| Frontend | https://dp3ng836djd04.cloudfront.net |
| Backend API | https://7uhda0ji1b.execute-api.us-west-2.amazonaws.com/prod/ |
| Mock Marketplace API | https://niumrztk8b.execute-api.us-west-2.amazonaws.com/prod/ |


## What This Builds

- Static frontend on S3 behind CloudFront
- Main Spring Boot Lambda behind API Gateway
- Mock marketplace Spring Boot Lambda behind a separate API Gateway
- DynamoDB tables:
  - `listings`
  - `marketplace_listings`
  - `activity_events`
- SQS publish queue and DLQ
- Mock marketplace delay queue and DLQ
- Secrets Manager secrets for webhook HMAC and mock API key

## Prerequisites

- Java 21
- Maven wrapper support through `./mvnw`
- Node.js and npm
- AWS CLI configured for account `072661200876`
- CDK bootstrap already completed in `us-west-2`

If the Homebrew Node install is broken locally, use the nvm Node path used during deployment:

```bash
export PATH=/Users/jung-eui-jin/.nvm/versions/node/v22.17.0/bin:$PATH
```

## Build

```bash
cd backend
./mvnw clean package

cd ../mock-marketplace
./mvnw clean package

cd ../cdk
npm install
npm run build
```

## Deploy

```bash
cd cdk
cdk deploy --require-approval never
```

After deployment, use the `FrontendUrl` output to open the app.

## Run A Basic End-to-End Check

1. Open the frontend:

```text
https://dp3ng836djd04.cloudfront.net
```

2. Create a listing with eBay selected.

3. Click the listing to open its detail view.

4. Wait 5-30 seconds and refresh the listing list or reopen the detail view.

Expected result for the normal path:

- The listing is created immediately.
- eBay status starts as `PENDING`.
- The mock marketplace sends a webhook.
- The status becomes `PUBLISHED`.
- The detail view shows a `publish_success` activity.

5. Click `Trigger item_sold` or `Trigger new_comment` in the listing detail view.

Expected result:

- The button calls the separate Mock Marketplace API.
- The mock signs and sends a webhook to the backend.
- The detail view shows `item_sold` or `new_comment` in the recent activity feed.

Equivalent API check:

```bash
curl -sS -X POST \
  https://7uhda0ji1b.execute-api.us-west-2.amazonaws.com/prod/listings \
  -H 'Content-Type: application/json' \
  -d '{"title":"Test listing","description":"Created from README","price":12345,"marketplaceIds":["ebay"]}'
```

Then fetch the returned listing ID:

```bash
curl -sS \
  https://7uhda0ji1b.execute-api.us-west-2.amazonaws.com/prod/listings/<listingId>
```

## Trigger Manual Marketplace Events

The frontend exposes `Trigger item_sold` and `Trigger new_comment` buttons in each listing's detail view.
If you prefer to test by API, send mock events after a listing is created:

```bash
curl -sS -X POST \
  https://niumrztk8b.execute-api.us-west-2.amazonaws.com/prod/mock/listings/<listingId>/events \
  -H 'Content-Type: application/json' \
  -d '{"eventType":"item_sold"}'
```

```bash
curl -sS -X POST \
  https://niumrztk8b.execute-api.us-west-2.amazonaws.com/prod/mock/listings/<listingId>/events \
  -H 'Content-Type: application/json' \
  -d '{"eventType":"new_comment"}'
```

Then call `GET /listings/<listingId>` on the backend API to verify the activity feed.

## Failure And DLQ Behavior

The mock marketplace event emitter randomly fails 20% of publish attempts. SQS retries the delay queue message up to 3 receives, then moves it to `mock-marketplace-dlq`.

The DLQ consumer sends a `publish_failed` webhook to the backend. The backend records an activity event and updates the relevant `marketplace_listings` row to `FAILED`.

Verified settings:

- `mock-marketplace-delay` redrive policy points to `mock-marketplace-dlq`
- `maxReceiveCount` is `3`
- `marketplace-mock-dlq` event source mapping is enabled

## Teardown

```bash
cd cdk
cdk destroy
```

The DynamoDB tables and frontend bucket use `RemovalPolicy.DESTROY`. The frontend bucket also uses `autoDeleteObjects`, so deleting the stack removes uploaded frontend assets.

## Cost Notes

The prototype is designed to stay low-cost when idle:

- Lambda is pay-per-request.
- DynamoDB uses on-demand capacity.
- SQS has a free tier for low request volume.
- S3 stores only the static frontend files.
- CloudFront may incur small request/data-transfer charges.
- Secrets Manager charges per stored secret.

Avoid leaving high-volume tests running because API Gateway, Lambda, CloudFront, DynamoDB, and SQS all charge by usage.

## Documentation

- Architecture: `docs/architecture.md`
- Data lifecycle: `docs/data-lifecycle.md`
- Implementation tracker: `docs/implementation-plan.md`
- Assignment write-up: `APPROACH.md`
