package com.marketplace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Tables tables,
        Queues queues,
        Secrets secrets,
        Marketplaces marketplaces
) {
    public record Tables(
            String listings,
            String marketplaceListings,
            String activityEvents
    ) {}

    public record Queues(
            String publishQueueUrl
    ) {}

    public record Secrets(
            String webhookSecretArn,
            String mockApiKeyArn
    ) {}

    public record Marketplaces(
            String ebayPublishUrl
    ) {}
}
