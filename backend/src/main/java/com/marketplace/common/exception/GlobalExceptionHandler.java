package com.marketplace.common.exception;

import com.marketplace.listing.exception.DuplicateListingException;
import com.marketplace.listing.exception.ListingNotFoundException;
import com.marketplace.webhook.exception.WebhookSignatureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DuplicateListingException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateListingException e) {
        return ResponseEntity.status(409).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(ListingNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ListingNotFoundException e) {
        return ResponseEntity.status(404).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(WebhookSignatureException.class)
    public ResponseEntity<ErrorResponse> handleInvalidSignature(WebhookSignatureException e) {
        return ResponseEntity.status(401).body(new ErrorResponse(e.getMessage()));
    }

    public record ErrorResponse(String message) {}
}
