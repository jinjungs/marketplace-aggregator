package com.marketplace.webhook.exception;

public class WebhookSignatureException extends RuntimeException {
    public WebhookSignatureException() {
        super("Invalid webhook signature");
    }
}
