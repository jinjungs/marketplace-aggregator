package com.marketplace.listing.dto;

import java.math.BigDecimal;
import java.util.List;

// Used for GET /listings/{listingId} (detail view — includes full activity feed)
public record ListingDetailResponse(
        String listingId,
        String sellerId,
        String title,
        String description,
        BigDecimal price,
        String createdAt,
        String updatedAt,
        List<ListingResponse.MarketplaceStatus> marketplaceStatuses,
        List<ListingResponse.ActivityEvent> activities
) {}
