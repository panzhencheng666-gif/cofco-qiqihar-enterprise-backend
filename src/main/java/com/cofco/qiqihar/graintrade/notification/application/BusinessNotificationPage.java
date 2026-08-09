package com.cofco.qiqihar.graintrade.notification.application;

import java.util.List;

public record BusinessNotificationPage(List<BusinessNotification> items, long unreadCount) {
    public BusinessNotificationPage {
        items = List.copyOf(items);
    }
}
