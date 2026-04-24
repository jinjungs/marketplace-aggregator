package com.marketplace.common;

import com.marketplace.listing.DuplicateListingException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DuplicateListingException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateListingException e) {
        return ResponseEntity.status(409).body(new ErrorResponse(e.getMessage()));
    }

    public record ErrorResponse(String message) {}
}
