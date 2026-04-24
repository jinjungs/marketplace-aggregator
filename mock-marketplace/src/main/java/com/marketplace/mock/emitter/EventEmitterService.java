package com.marketplace.mock.emitter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.mock.config.AppProperties;
import com.marketplace.mock.config.SecretsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EventEmitterService {

    private final AppProperties props;
    private final SecretsService secretsService;
    private final ObjectMapper objectMapper;

    private final Random random = new Random();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public void emit(String listingId) {
        if (random.nextInt(100) < 20) {
            throw new RuntimeException("Simulated marketplace failure (20% rate)");
        }
        sendWebhook(listingId, "publish_success");
    }

    public void emitEvent(String listingId, String eventType) {
        sendWebhook(listingId, eventType);
    }

    public void emitFailure(String listingId) {
        sendWebhook(listingId, "publish_failed");
    }

    private void sendWebhook(String listingId, String eventType) {
        try {
            Map<String, Object> payload = Map.of(
                    "event",         eventType,
                    "listingId",     listingId,
                    "marketplaceId", "ebay",
                    "data",          buildEventData(eventType)
            );

            String body = objectMapper.writeValueAsString(payload);
            String signature = sign(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(props.backendWebhookUrl()))
                    .header("Content-Type", "application/json")
                    .header("X-Marketplace-Signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Webhook delivery failed: " + response.statusCode());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to send webhook", e);
        }
    }

    private Map<String, Object> buildEventData(String eventType) {
        return switch (eventType) {
            case "publish_success" -> Map.of("externalListingId", "mock-" + UUID.randomUUID().toString().substring(0, 8));
            case "item_sold"       -> Map.of("buyerName", "Test Buyer", "salePrice", 1500000, "transactionId", UUID.randomUUID().toString());
            case "new_comment"     -> Map.of("comment", "Is this still available?", "buyerName", "Test Buyer");
            default                -> Map.of("reason", "Simulated marketplace error");
        };
    }

    private String sign(String body) {
        try {
            String secret = secretsService.getSecret(props.webhookSecretArn());
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes()));
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign webhook", e);
        }
    }
}
