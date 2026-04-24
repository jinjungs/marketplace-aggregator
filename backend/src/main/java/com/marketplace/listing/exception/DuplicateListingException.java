package com.marketplace.listing.exception;

public class DuplicateListingException extends RuntimeException {
    public DuplicateListingException(String listingId) {
        super("Listing already exists: " + listingId);
    }
}
