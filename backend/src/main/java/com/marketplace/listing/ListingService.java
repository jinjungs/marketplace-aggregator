package com.marketplace.listing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.config.AppProperties;
import com.marketplace.listing.dto.CreateListingRequest;
import com.marketplace.listing.dto.ListingDetailResponse;
import com.marketplace.listing.dto.ListingResponse;
import com.marketplace.listing.dto.ListingResponse.ActivityEvent;
import com.marketplace.listing.dto.ListingResponse.MarketplaceStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ListingService {

    private final DynamoDbClient dynamoDb;
    private final SqsClient sqs;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    public List<ListingResponse> getAll() {
        return dynamoDb.scan(ScanRequest.builder()
                .tableName(props.tables().listings())
                .build())
                .items().stream()
                .map(item -> {
                    String listingId = item.get("listingId").s();
                    return new ListingResponse(
                            listingId,
                            item.get("sellerId").s(),
                            item.get("title").s(),
                            item.get("description").s(),
                            new BigDecimal(item.get("price").n()),
                            item.get("createdAt").s(),
                            item.get("updatedAt").s(),
                            getMarketplaceStatuses(listingId)
                    );
                })
                .toList();
    }

    public ListingDetailResponse getById(String listingId) {
        var result = dynamoDb.getItem(r -> r
                .tableName(props.tables().listings())
                .key(Map.of("listingId", AttributeValue.fromS(listingId))));

        if (!result.hasItem()) {
            throw new ListingNotFoundException(listingId);
        }

        Map<String, AttributeValue> item = result.item();
        return new ListingDetailResponse(
                listingId,
                item.get("sellerId").s(),
                item.get("title").s(),
                item.get("description").s(),
                new BigDecimal(item.get("price").n()),
                item.get("createdAt").s(),
                item.get("updatedAt").s(),
                getMarketplaceStatuses(listingId),
                getRecentActivities(listingId)
        );
    }

    private List<MarketplaceStatus> getMarketplaceStatuses(String listingId) {
        return dynamoDb.query(QueryRequest.builder()
                .tableName(props.tables().marketplaceListings())
                .keyConditionExpression("listingId = :listingId")
                .expressionAttributeValues(Map.of(":listingId", AttributeValue.fromS(listingId)))
                .build())
                .items().stream()
                .map(item -> new MarketplaceStatus(
                        item.get("marketplaceId").s(),
                        item.get("status").s(),
                        getOrNull(item, "externalListingId"),
                        getOrNull(item, "publishedAt"),
                        getOrNull(item, "failReason")
                ))
                .toList();
    }

    private List<ActivityEvent> getRecentActivities(String listingId) {
        return dynamoDb.query(QueryRequest.builder()
                .tableName(props.tables().activityEvents())
                .keyConditionExpression("listingId = :listingId")
                .expressionAttributeValues(Map.of(":listingId", AttributeValue.fromS(listingId)))
                .scanIndexForward(false) // Newest first
                .limit(10)
                .build())
                .items().stream()
                .map(item -> {
                    Object data = null;
                    if (item.containsKey("data")) {
                        try {
                            data = objectMapper.readValue(item.get("data").s(),
                                    new TypeReference<Map<String, Object>>() {});
                        } catch (Exception ignored) {}
                    }
                    return new ActivityEvent(
                            item.get("eventId").s(),
                            item.get("marketplaceId").s(),
                            item.get("eventType").s(),
                            item.get("timestamp").s(),
                            data
                    );
                })
                .toList();
    }

    private String getOrNull(Map<String, AttributeValue> item, String key) {
        AttributeValue val = item.get(key);
        return (val != null && val.s() != null) ? val.s() : null;
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
