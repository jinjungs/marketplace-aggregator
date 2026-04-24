package com.marketplace.listing;

import com.marketplace.listing.dto.CreateListingRequest;
import com.marketplace.listing.dto.CreateListingResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/listings")
@RequiredArgsConstructor
public class ListingController {

    private final ListingService listingService;

    @PostMapping
    public ResponseEntity<CreateListingResponse> create(@RequestBody CreateListingRequest request) {
        String listingId = listingService.create(request);
        return ResponseEntity.ok(new CreateListingResponse(listingId));
    }
}
