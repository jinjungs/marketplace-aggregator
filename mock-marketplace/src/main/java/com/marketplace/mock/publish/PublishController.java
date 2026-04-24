package com.marketplace.mock.publish;

import com.marketplace.mock.emitter.EventEmitterService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/mock/listings")
@RequiredArgsConstructor
public class PublishController {

    private static final Set<String> ALLOWED_EVENT_TYPES = Set.of("item_sold", "new_comment");

    private final PublishService publishService;
    private final EventEmitterService eventEmitterService;

    @PostMapping("/publish")
    public ResponseEntity<Map<String, String>> publish(@RequestBody Map<String, String> body) {
        String listingId = body.get("listingId");
        publishService.enqueue(listingId);
        return ResponseEntity.accepted().body(Map.of("status", "accepted", "listingId", listingId));
    }

    @PostMapping("/{listingId}/events")
    public ResponseEntity<Map<String, String>> triggerEvent(
            @PathVariable String listingId,
            @RequestBody Map<String, String> body) {
        String eventType = body.get("eventType");
        if (!ALLOWED_EVENT_TYPES.contains(eventType)) {
            return ResponseEntity.badRequest().body(Map.of("error", "eventType must be one of: " + ALLOWED_EVENT_TYPES));
        }
        eventEmitterService.emitEvent(listingId, eventType);
        return ResponseEntity.ok(Map.of("status", "sent", "eventType", eventType, "listingId", listingId));
    }
}
