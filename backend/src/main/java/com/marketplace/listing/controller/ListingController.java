package com.marketplace.listing.controller;

import com.marketplace.listing.dto.CreateListingRequest;
import com.marketplace.listing.dto.CreateListingResponse;
import com.marketplace.listing.dto.ListingDetailResponse;
import com.marketplace.listing.dto.ListingResponse;
import com.marketplace.listing.service.ListingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/listings")
@RequiredArgsConstructor
public class ListingController {

    private final ListingService listingService;

    @GetMapping
    public ResponseEntity<List<ListingResponse>> getAll() {
        return ResponseEntity.ok(listingService.getAll());
    }

    @GetMapping("/{listingId}")
    public ResponseEntity<ListingDetailResponse> getById(@PathVariable String listingId) {
        return ResponseEntity.ok(listingService.getById(listingId));
    }

    @PostMapping
    public ResponseEntity<CreateListingResponse> create(@RequestBody CreateListingRequest request) {
        String listingId = listingService.create(request);
        return ResponseEntity.ok(new CreateListingResponse(listingId));
    }
}
