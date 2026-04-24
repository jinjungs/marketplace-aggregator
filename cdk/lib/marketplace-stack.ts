import * as cdk from 'aws-cdk-lib/core';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as lambdaEventSources from 'aws-cdk-lib/aws-lambda-event-sources';
import * as apigateway from 'aws-cdk-lib/aws-apigateway';
import * as path from 'path';
import { Construct } from 'constructs';

export class MarketplaceStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    // -------------------------
    // DynamoDB Tables
    // -------------------------

    const listingsTable = new dynamodb.Table(this, 'ListingsTable', {
      tableName: 'listings',
      partitionKey: { name: 'listingId', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    const marketplaceListingsTable = new dynamodb.Table(this, 'MarketplaceListingsTable', {
      tableName: 'marketplace_listings',
      partitionKey: { name: 'listingId', type: dynamodb.AttributeType.STRING },
      sortKey: { name: 'marketplaceId', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    const activityEventsTable = new dynamodb.Table(this, 'ActivityEventsTable', {
      tableName: 'activity_events',
      partitionKey: { name: 'listingId', type: dynamodb.AttributeType.STRING },
      sortKey: { name: 'sk', type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    new cdk.CfnOutput(this, 'ListingsTableName', { value: listingsTable.tableName });
    new cdk.CfnOutput(this, 'MarketplaceListingsTableName', { value: marketplaceListingsTable.tableName });
    new cdk.CfnOutput(this, 'ActivityEventsTableName', { value: activityEventsTable.tableName });

    // -------------------------
    // SQS — Publish Queue + DLQ
    // -------------------------

    const publishDlq = new sqs.Queue(this, 'PublishDlq', {
      queueName: 'marketplace-publish-dlq',
      retentionPeriod: cdk.Duration.days(14),
    });

    const publishQueue = new sqs.Queue(this, 'PublishQueue', {
      queueName: 'marketplace-publish',
      visibilityTimeout: cdk.Duration.seconds(30),
      deadLetterQueue: {
        queue: publishDlq,
        maxReceiveCount: 3,
      },
    });

    new cdk.CfnOutput(this, 'PublishQueueUrl', { value: publishQueue.queueUrl });
    new cdk.CfnOutput(this, 'PublishDlqUrl', { value: publishDlq.queueUrl });

    // -------------------------
    // Secrets Manager
    // -------------------------

    // HMAC secret shared between backend and mock marketplace for webhook signature verification
    const webhookSecret = new secretsmanager.Secret(this, 'WebhookSecret', {
      secretName: 'marketplace/webhook-secret',
      generateSecretString: {
        excludePunctuation: true,
        passwordLength: 64,
      },
    });

    // API key used by backend when calling mock marketplace endpoints
    const mockApiKey = new secretsmanager.Secret(this, 'MockApiKey', {
      secretName: 'marketplace/mock-api-key',
      generateSecretString: {
        excludePunctuation: true,
        passwordLength: 32,
      },
    });

    new cdk.CfnOutput(this, 'WebhookSecretArn', { value: webhookSecret.secretArn });
    new cdk.CfnOutput(this, 'MockApiKeyArn', { value: mockApiKey.secretArn });

    // -------------------------
    // Backend Lambda
    // -------------------------

    const backendLambda = new lambda.Function(this, 'BackendLambda', {
      functionName: 'marketplace-backend',
      runtime: lambda.Runtime.JAVA_21,
      handler: 'com.marketplace.StreamLambdaHandler::handleRequest',
      code: lambda.Code.fromAsset(
        path.join(__dirname, '../../backend/target/backend-0.0.1-SNAPSHOT.jar')
      ),
      memorySize: 512,
      timeout: cdk.Duration.seconds(30),
      environment: {
        PUBLISH_QUEUE_URL:    publishQueue.queueUrl,
        WEBHOOK_SECRET_ARN:   webhookSecret.secretArn,
        MOCK_API_KEY_ARN:     mockApiKey.secretArn,
      },
    });

    listingsTable.grantReadWriteData(backendLambda);
    marketplaceListingsTable.grantReadWriteData(backendLambda);
    activityEventsTable.grantReadWriteData(backendLambda);
    publishQueue.grantSendMessages(backendLambda);
    webhookSecret.grantRead(backendLambda);
    mockApiKey.grantRead(backendLambda);

    // -------------------------
    // API Gateway (main)
    // -------------------------

    const api = new apigateway.LambdaRestApi(this, 'BackendApi', {
      handler: backendLambda,
      proxy: true,
      defaultCorsPreflightOptions: {
        allowOrigins: apigateway.Cors.ALL_ORIGINS,
        allowMethods: apigateway.Cors.ALL_METHODS,
      },
    });

    new cdk.CfnOutput(this, 'ApiUrl', { value: api.url });

    // -------------------------
    // Mock Marketplace — SQS Delay Queue + DLQ
    // -------------------------

    const mockDlq = new sqs.Queue(this, 'MockDlq', {
      queueName: 'mock-marketplace-dlq',
      retentionPeriod: cdk.Duration.days(14),
      visibilityTimeout: cdk.Duration.seconds(120),
    });

    const mockDelayQueue = new sqs.Queue(this, 'MockDelayQueue', {
      queueName: 'mock-marketplace-delay',
      visibilityTimeout: cdk.Duration.seconds(120),
      deadLetterQueue: {
        queue: mockDlq,
        maxReceiveCount: 3,
      },
    });

    // -------------------------
    // Mock Marketplace — HTTP Lambda (POST /mock/listings/publish)
    // -------------------------

    const mockHttpLambda = new lambda.Function(this, 'MockHttpLambda', {
      functionName: 'marketplace-mock-http',
      runtime: lambda.Runtime.JAVA_21,
      handler: 'com.marketplace.mock.StreamLambdaHandler::handleRequest',
      code: lambda.Code.fromAsset(
        path.join(__dirname, '../../mock-marketplace/target/mock-marketplace-0.0.1-SNAPSHOT.jar')
      ),
      memorySize: 512,
      timeout: cdk.Duration.seconds(30),
      environment: {
        DELAY_QUEUE_URL:      mockDelayQueue.queueUrl,
        BACKEND_WEBHOOK_URL:  `${api.url}webhooks`,
        WEBHOOK_SECRET_ARN:   webhookSecret.secretArn,
      },
    });

    mockDelayQueue.grantSendMessages(mockHttpLambda);
    webhookSecret.grantRead(mockHttpLambda);

    const mockApi = new apigateway.LambdaRestApi(this, 'MockApi', {
      handler: mockHttpLambda,
      proxy: true,
    });

    // -------------------------
    // Mock Marketplace — Event Emitter Lambda (SQS consumer)
    // -------------------------

    const mockEmitterLambda = new lambda.Function(this, 'MockEmitterLambda', {
      functionName: 'marketplace-mock-emitter',
      runtime: lambda.Runtime.JAVA_21,
      handler: 'com.marketplace.mock.emitter.EventEmitterHandler::handleRequest',
      code: lambda.Code.fromAsset(
        path.join(__dirname, '../../mock-marketplace/target/mock-marketplace-0.0.1-SNAPSHOT.jar')
      ),
      memorySize: 512,
      timeout: cdk.Duration.seconds(60),
      environment: {
        DELAY_QUEUE_URL:      mockDelayQueue.queueUrl,
        BACKEND_WEBHOOK_URL:  `${api.url}webhooks`,
        WEBHOOK_SECRET_ARN:   webhookSecret.secretArn,
      },
    });

    mockEmitterLambda.addEventSource(
      new lambdaEventSources.SqsEventSource(mockDelayQueue, { batchSize: 1 })
    );
    webhookSecret.grantRead(mockEmitterLambda);

    // -------------------------
    // Mock Marketplace — DLQ Consumer Lambda
    // -------------------------

    const mockDlqLambda = new lambda.Function(this, 'MockDlqLambda', {
      functionName: 'marketplace-mock-dlq',
      runtime: lambda.Runtime.JAVA_21,
      handler: 'com.marketplace.mock.emitter.DlqConsumerHandler::handleRequest',
      code: lambda.Code.fromAsset(
        path.join(__dirname, '../../mock-marketplace/target/mock-marketplace-0.0.1-SNAPSHOT.jar')
      ),
      memorySize: 512,
      timeout: cdk.Duration.seconds(60),
      environment: {
        DELAY_QUEUE_URL:      mockDelayQueue.queueUrl,
        BACKEND_WEBHOOK_URL:  `${api.url}webhooks`,
        WEBHOOK_SECRET_ARN:   webhookSecret.secretArn,
      },
    });

    mockDlqLambda.addEventSource(
      new lambdaEventSources.SqsEventSource(mockDlq, { batchSize: 1 })
    );
    webhookSecret.grantRead(mockDlqLambda);

    new cdk.CfnOutput(this, 'MockApiUrl', { value: mockApi.url });
    // EBAY_PUBLISH_URL for BackendLambda must be set separately after deployment
    // to avoid circular dependency between BackendApi and MockApi.
    // Run: aws lambda update-function-configuration --function-name marketplace-backend
    //      --environment Variables={...,EBAY_PUBLISH_URL=<MockApiUrl>mock/listings/publish}
  }
}
