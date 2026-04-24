package com.marketplace.webhook;

public class WebhookSignatureException extends RuntimeException {
    public WebhookSignatureException() {
        super("Invalid webhook signature");
    }
}
