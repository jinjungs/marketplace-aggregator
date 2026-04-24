package com.marketplace.consumer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.marketplace.MarketplaceAdapterFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class PublishConsumerService {

    private final MarketplaceAdapterFactory adapterFactory;
    private final ObjectMapper objectMapper;

    public void process(String messageBody) {
        try {
            Map<?, ?> message = objectMapper.readValue(messageBody, Map.class);
            String listingId = (String) message.get("listingId");
            String marketplaceId = (String) message.get("marketplaceId");

            adapterFactory.get(marketplaceId).publish(listingId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to process publish message", e);
        }
    }
}
