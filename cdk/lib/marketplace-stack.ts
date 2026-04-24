import * as cdk from 'aws-cdk-lib/core';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
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
  }
}
