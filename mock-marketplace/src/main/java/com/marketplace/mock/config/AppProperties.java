package com.marketplace.mock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String delayQueueUrl,
        String backendWebhookUrl,
        String webhookSecretArn
) {}
