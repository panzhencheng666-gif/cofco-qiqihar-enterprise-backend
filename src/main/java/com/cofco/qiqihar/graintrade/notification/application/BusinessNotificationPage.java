package com.cofco.qiqihar.graintrade.notification.application;

import java.util.List;

public record BusinessNotificationPage(List<BusinessNotification> items, long unreadCount, long currentSequence) {
    public BusinessNotificationPage {
        items = List.copyOf(items);
    }
}
