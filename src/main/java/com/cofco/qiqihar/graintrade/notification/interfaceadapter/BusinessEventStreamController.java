package com.cofco.qiqihar.graintrade.notification.interfaceadapter;

import com.cofco.qiqihar.graintrade.notification.application.BusinessEventStreamService;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class BusinessEventStreamController {
    private final BusinessEventStreamService service;

    public BusinessEventStreamController(BusinessEventStreamService service) {
        this.service = service;
    }

    @GetMapping(value = "/api/v1/business-events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(
            @RequestParam(required = false) Long after,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        return service.stream(resolveCursor(after, lastEventId));
    }

    private static long resolveCursor(Long after, String lastEventId) {
        if (after != null) {
            if (after < 0) throw invalidCursor();
            return after;
        }
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0;
        }
        try {
            long cursor = Long.parseLong(lastEventId.trim());
            if (cursor < 0) throw invalidCursor();
            return cursor;
        } catch (NumberFormatException invalidCursor) {
            throw invalidCursor();
        }
    }

    private static ClientRequestException invalidCursor() {
        return new ClientRequestException(
                "INVALID_EVENT_CURSOR", "Realtime update cursor is invalid");
    }
}
