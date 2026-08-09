package com.cofco.qiqihar.graintrade.notification.interfaceadapter;

import com.cofco.qiqihar.graintrade.notification.application.BusinessNotification;
import com.cofco.qiqihar.graintrade.notification.application.BusinessNotificationPage;
import com.cofco.qiqihar.graintrade.notification.application.BusinessNotificationService;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BusinessNotificationController {
    private final BusinessNotificationService service;

    public BusinessNotificationController(BusinessNotificationService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/notifications")
    ApiResponse<PageResponse> list() {
        return new ApiResponse<>(PageResponse.from(service.list()));
    }

    @PostMapping("/api/v1/notifications/{eventId}/read")
    ApiResponse<ItemResponse> markRead(@PathVariable UUID eventId) {
        return new ApiResponse<>(ItemResponse.from(service.markRead(eventId)));
    }

    record PageResponse(List<ItemResponse> items, long unreadCount) {
        static PageResponse from(BusinessNotificationPage page) {
            return new PageResponse(page.items().stream().map(ItemResponse::from).toList(), page.unreadCount());
        }
    }

    record ItemResponse(
            UUID id, long sequence, String aggregateType, String aggregateId,
            String actionCode, String productCode, List<String> regionCodes,
            Instant occurredAt, boolean read) {
        static ItemResponse from(BusinessNotification notification) {
            return new ItemResponse(
                    notification.id(), notification.sequence(), notification.aggregateType(),
                    notification.aggregateId(), notification.actionCode(), notification.productCode(),
                    notification.regionCodes(), notification.occurredAt(), notification.read());
        }
    }
}
