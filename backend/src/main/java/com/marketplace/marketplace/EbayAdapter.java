package com.marketplace.marketplace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.config.AppProperties;
import com.marketplace.config.SecretsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class EbayAdapter implements MarketplaceAdapter {

    private final AppProperties props;
    private final SecretsService secretsService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public void publish(String listingId) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("listingId", listingId));
            String apiKey = secretsService.getSecret(props.secrets().mockApiKeyArn());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(props.marketplaces().ebayPublishUrl()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 202) {
                throw new RuntimeException("Mock marketplace returned " + response.statusCode());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to publish to eBay mock", e);
        }
    }
}
