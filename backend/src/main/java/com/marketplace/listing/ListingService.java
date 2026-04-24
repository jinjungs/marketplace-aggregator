package com.marketplace.listing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.config.AppProperties;
import com.marketplace.listing.dto.CreateListingRequest;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class ListingService {

    private final DynamoDbClient dynamoDb;
    private final SqsClient sqs;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    public ListingService(DynamoDbClient dynamoDb, SqsClient sqs,
                          AppProperties props, ObjectMapper objectMapper) {
        this.dynamoDb = dynamoDb;
        this.sqs = sqs;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    public String create(CreateListingRequest request) {
        String listingId = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        saveListing(listingId, request, now);
        saveMarketplaceListings(listingId, request, now);
        enqueuePublishMessages(listingId, request);

        return listingId;
    }

    private void saveListing(String listingId, CreateListingRequest request, String now) {
        Map<String, AttributeValue> item = Map.of(
                "listingId",   AttributeValue.fromS(listingId),
                "sellerId",    AttributeValue.fromS("seller-001"), // TODO: replace with real auth
                "title",       AttributeValue.fromS(request.title()),
                "description", AttributeValue.fromS(request.description()),
                "price",       AttributeValue.fromN(request.price().toPlainString()),
                "createdAt",   AttributeValue.fromS(now),
                "updatedAt",   AttributeValue.fromS(now)
        );

        try {
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(props.tables().listings())
                    .item(item)
                    .conditionExpression("attribute_not_exists(listingId)")
                    .build());
        } catch (ConditionalCheckFailedException e) {
            throw new DuplicateListingException(listingId);
        }
    }

    private void saveMarketplaceListings(String listingId, CreateListingRequest request, String now) {
        for (String marketplaceId : request.marketplaceIds()) {
            Map<String, AttributeValue> item = Map.of(
                    "listingId",     AttributeValue.fromS(listingId),
                    "marketplaceId", AttributeValue.fromS(marketplaceId),
                    "status",        AttributeValue.fromS("PENDING"),
                    "createdAt",     AttributeValue.fromS(now),
                    "updatedAt",     AttributeValue.fromS(now)
            );

            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(props.tables().marketplaceListings())
                    .item(item)
                    .build());
        }
    }

    private void enqueuePublishMessages(String listingId, CreateListingRequest request) {
        for (String marketplaceId : request.marketplaceIds()) {
            try {
                String body = objectMapper.writeValueAsString(
                        Map.of("listingId", listingId, "marketplaceId", marketplaceId)
                );
                sqs.sendMessage(SendMessageRequest.builder()
                        .queueUrl(props.queues().publishQueueUrl())
                        .messageBody(body)
                        .build());
            } catch (Exception e) {
                throw new RuntimeException("Failed to enqueue publish message", e);
            }
        }
    }
}
