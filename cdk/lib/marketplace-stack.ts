import * as cdk from 'aws-cdk-lib/core';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
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
  }
}
