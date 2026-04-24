package com.marketplace.mock.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.mock.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.Map;
import java.util.Random;

@Service
@RequiredArgsConstructor
public class PublishService {

    private final SqsClient sqsClient;
    private final AppProperties props;
    private final ObjectMapper objectMapper;
    private final Random random = new Random();

    public void enqueue(String listingId) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("listingId", listingId));
            int delaySeconds = 5 + random.nextInt(26); // Random 5-30 second delay

            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(props.delayQueueUrl())
                    .messageBody(body)
                    .delaySeconds(delaySeconds)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to enqueue publish message", e);
        }
    }
}
