package com.marketplace.consumer.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.marketplace.BackendApplication;
import com.marketplace.consumer.service.PublishConsumerService;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.SpringApplication;

public class PublishConsumerHandler implements RequestHandler<SQSEvent, Void> {

    private static final ApplicationContext context =
            SpringApplication.run(BackendApplication.class);

    @Override
    public Void handleRequest(SQSEvent event, Context lambdaContext) {
        PublishConsumerService service = context.getBean(PublishConsumerService.class);

        for (SQSEvent.SQSMessage message : event.getRecords()) {
            service.process(message.getBody());
        }
        return null;
    }
}
