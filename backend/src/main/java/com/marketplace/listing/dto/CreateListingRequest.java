package com.marketplace.listing.dto;

import java.math.BigDecimal;
import java.util.List;

public record CreateListingRequest(
        String title,
        String description,
        BigDecimal price,
        List<String> marketplaceIds
) {}
