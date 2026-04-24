package com.marketplace.webhook;

import java.util.Map;

public record WebhookRequest(
        String event,
        String listingId,
        String marketplaceId,
        Map<String, Object> data
) {}
