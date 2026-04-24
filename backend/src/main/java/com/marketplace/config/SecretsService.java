package com.marketplace.config;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class SecretsService {

    private final SecretsManagerClient secretsManagerClient;
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public String getSecret(String secretArn) {
        return cache.computeIfAbsent(secretArn, arn ->
                secretsManagerClient.getSecretValue(
                        GetSecretValueRequest.builder().secretId(arn).build()
                ).secretString()
        );
    }
}
