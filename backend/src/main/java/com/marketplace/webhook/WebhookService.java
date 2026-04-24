package com.marketplace.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.config.AppProperties;
import com.marketplace.config.SecretsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WebhookService {

    private final DynamoDbClient dynamoDb;
    private final SecretsService secretsService;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    public void process(String rawBody, String signatureHeader) {
        verifySignature(rawBody, signatureHeader);

        WebhookRequest request;
        try {
            request = objectMapper.readValue(rawBody, WebhookRequest.class);
        } catch (Exception e) {
            throw new RuntimeException("Invalid webhook body", e);
        }

        saveActivityEvent(request.listingId(), request);
        updateMarketplaceListingStatus(request.listingId(), request);
    }

    private void verifySignature(String rawBody, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            throw new WebhookSignatureException();
        }

        try {
            String secret = secretsService.getSecret(props.secrets().webhookSecretArn());
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(rawBody.getBytes()));

            if (!MessageDigest.isEqual(expected.getBytes(), signatureHeader.getBytes())) {
                throw new WebhookSignatureException();
            }
        } catch (WebhookSignatureException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Signature verification failed", e);
        }
    }

    private void saveActivityEvent(String listingId, WebhookRequest request) {
        String eventId = UUID.randomUUID().toString();
        String timestamp = Instant.now().toString();
        String sk = timestamp + "#" + eventId;

        try {
            String dataJson = objectMapper.writeValueAsString(request.data());
            dynamoDb.putItem(r -> r
                    .tableName(props.tables().activityEvents())
                    .item(Map.of(
                            "listingId",     AttributeValue.fromS(listingId),
                            "sk",            AttributeValue.fromS(sk),
                            "eventId",       AttributeValue.fromS(eventId),
                            "marketplaceId", AttributeValue.fromS(request.marketplaceId()),
                            "eventType",     AttributeValue.fromS(request.event()),
                            "timestamp",     AttributeValue.fromS(timestamp),
                            "data",          AttributeValue.fromS(dataJson)
                    ))
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to save activity event", e);
        }
    }

    private void updateMarketplaceListingStatus(String listingId, WebhookRequest request) {
        String now = Instant.now().toString();

        switch (request.event()) {
            case "publish_success" -> {
                String externalListingId = (String) request.data().get("externalListingId");
                dynamoDb.updateItem(UpdateItemRequest.builder()
                        .tableName(props.tables().marketplaceListings())
                        .key(Map.of(
                                "listingId",     AttributeValue.fromS(listingId),
                                "marketplaceId", AttributeValue.fromS(request.marketplaceId())
                        ))
                        .updateExpression("SET #status = :status, externalListingId = :eid, publishedAt = :publishedAt, updatedAt = :updatedAt")
                        .expressionAttributeNames(Map.of("#status", "status"))
                        .expressionAttributeValues(Map.of(
                                ":status",      AttributeValue.fromS("PUBLISHED"),
                                ":eid",         AttributeValue.fromS(externalListingId != null ? externalListingId : ""),
                                ":publishedAt", AttributeValue.fromS(now),
                                ":updatedAt",   AttributeValue.fromS(now)
                        ))
                        .build());
            }
            case "publish_failed" -> {
                dynamoDb.updateItem(UpdateItemRequest.builder()
                        .tableName(props.tables().marketplaceListings())
                        .key(Map.of(
                                "listingId",     AttributeValue.fromS(listingId),
                                "marketplaceId", AttributeValue.fromS(request.marketplaceId())
                        ))
                        .updateExpression("SET #status = :status, updatedAt = :updatedAt")
                        .expressionAttributeNames(Map.of("#status", "status"))
                        .expressionAttributeValues(Map.of(
                                ":status",    AttributeValue.fromS("FAILED"),
                                ":updatedAt", AttributeValue.fromS(now)
                        ))
                        .build());
            }
            default -> { /* item_sold and new_comment only create activity events; status stays unchanged */ }
        }
    }
}
