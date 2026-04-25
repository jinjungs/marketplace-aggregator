# Marketplace Aggregator

A serverless prototype for publishing a seller's listing to a mocked marketplace and collecting marketplace events in one activity feed.

## Documentation

- Assignment write-up: [APPROACH.md](APPROACH.md)


## Deployed URLs

| Name | URL |
|---|---|
| Frontend | https://dp3ng836djd04.cloudfront.net |
| Backend API | available in CloudFormation outputs |
| Mock Marketplace API | available in CloudFormation outputs |

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

- Java 21. The repo includes Maven wrappers, so a separate Maven install is not required.
- Node.js and npm.
- AWS CLI configured with credentials for the AWS account you want to deploy into.
- CDK bootstrap completed in the target account and region.

The deployed demo uses `us-west-2`. From a clean AWS account, bootstrap once:

```bash
cd cdk
npm install
npx cdk bootstrap aws://<account-id>/us-west-2
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
npx cdk deploy --require-approval never
```

After deployment, use the `FrontendUrl` output to open the app.

## Environment Variables

No local `.env` file is required for deployment. CDK creates the required queues, secrets, and API URLs, then injects the Lambda environment variables automatically. Secret values are generated in AWS Secrets Manager and are never committed to the repo.

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

## Trigger Manual Marketplace Events


Click `Trigger item_sold` or `Trigger new_comment` in the listing detail view.

Expected result:

- The button calls the separate Mock Marketplace API.
- The mock signs and sends a webhook to the backend.
- The detail view shows `item_sold` or `new_comment` in the recent activity feed.

## Failure And DLQ Behavior

There are two queue boundaries:

1. **Backend publish queue**: `marketplace-publish` carries work from the backend API to the publish consumer Lambda. If the consumer cannot call the mock marketplace successfully, SQS retries the message up to 3 receives, then moves it to `marketplace-publish-dlq`.

2. **Mock marketplace delay queue**: `mock-marketplace-delay` simulates asynchronous third-party processing. The mock event emitter randomly fails 20% of publish attempts. SQS retries the delay queue message up to 3 receives, then moves it to `mock-marketplace-dlq`.

The mock marketplace DLQ has a consumer that sends a `publish_failed` webhook to the backend. The backend records an activity event and updates the relevant `marketplace_listings` row to `FAILED`.

Verified settings:

- `marketplace-publish` redrive policy points to `marketplace-publish-dlq`
- `mock-marketplace-delay` redrive policy points to `mock-marketplace-dlq`
- `maxReceiveCount` is `3`
- `marketplace-mock-dlq` event source mapping is enabled

## Teardown

```bash
cd cdk
npx cdk destroy
```

The DynamoDB tables and frontend bucket use `RemovalPolicy.DESTROY`. The frontend bucket also uses `autoDeleteObjects`, so deleting the stack removes uploaded frontend assets.

## Cost Notes

Estimated cost to leave the deployed prototype running for one day with light manual testing is roughly **$0.03-$0.10/day**. Most usage-based services remain near zero at this scale; the main fixed cost is Secrets Manager:

| Service | Cost behavior |
|---|---|
| Secrets Manager | About $0.80/month for 2 secrets, or ~$0.03/day |
| Lambda | Pay-per-request; light testing is usually within free tier |
| DynamoDB on-demand | Pay-per-request; light testing is near zero |
| SQS | Light testing is usually within free tier |
| S3 + CloudFront | Small storage/request/data-transfer charges |
| API Gateway REST API | Request-based; light testing is near zero |

Avoid leaving high-volume tests running because API Gateway, Lambda, CloudFront, DynamoDB, and SQS all charge by usage.
