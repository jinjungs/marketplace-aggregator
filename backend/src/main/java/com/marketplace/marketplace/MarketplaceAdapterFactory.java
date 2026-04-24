package com.marketplace.marketplace;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class MarketplaceAdapterFactory {

    private final Map<String, MarketplaceAdapter> adapters;

    public MarketplaceAdapterFactory(EbayAdapter ebayAdapter) {
        this.adapters = Map.of("ebay", ebayAdapter);
    }

    public MarketplaceAdapter get(String marketplaceId) {
        MarketplaceAdapter adapter = adapters.get(marketplaceId);
        if (adapter == null) {
            throw new UnsupportedOperationException("Unsupported marketplace: " + marketplaceId);
        }
        return adapter;
    }
}
