package com.marketplace.listing.dto;

import java.math.BigDecimal;
import java.util.List;

// Used for GET /listings (list view — no activity feed)
public record ListingResponse(
        String listingId,
        String sellerId,
        String title,
        String description,
        BigDecimal price,
        String createdAt,
        String updatedAt,
        List<MarketplaceStatus> marketplaceStatuses
) {
    public record MarketplaceStatus(
            String marketplaceId,
            String status,
            String externalListingId,
            String publishedAt,
            String failReason
    ) {}

    public record ActivityEvent(
            String eventId,
            String marketplaceId,
            String eventType,
            String timestamp,
            Object data
    ) {}
}
