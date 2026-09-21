package com.cofco.qiqihar.graintrade.notification.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.cofco.qiqihar.graintrade.notification.application.BusinessNotificationPage;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class BusinessNotificationControllerCursorTest {
    @Test
    void notificationResponseExposesTheSharedStreamCursor() {
        var page = new BusinessNotificationPage(List.of(), 0L, 9000L);

        var response = BusinessNotificationController.PageResponse.from(page);
        var json = new ObjectMapper().valueToTree(response);

        assertThat(json.get("currentSequence").asLong()).isEqualTo(9000L);
    }
}
