package com.marketplace.mock.emitter;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.mock.MockMarketplaceApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;

import java.util.Map;

public class DlqConsumerHandler implements RequestHandler<SQSEvent, Void> {

    private static final ApplicationContext context =
            SpringApplication.run(MockMarketplaceApplication.class);

    @Override
    public Void handleRequest(SQSEvent event, Context lambdaContext) {
        EventEmitterService service = context.getBean(EventEmitterService.class);
        ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

        for (SQSEvent.SQSMessage message : event.getRecords()) {
            try {
                Map<?, ?> body = objectMapper.readValue(message.getBody(), Map.class);
                String listingId = (String) body.get("listingId");
                service.emitFailure(listingId);
            } catch (Exception e) {
                lambdaContext.getLogger().log("DLQ processing failed: " + e.getMessage());
            }
        }
        return null;
    }
}
