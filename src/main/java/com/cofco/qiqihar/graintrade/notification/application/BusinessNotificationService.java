package com.cofco.qiqihar.graintrade.notification.application;

import com.cofco.qiqihar.graintrade.shared.application.ResourceNotFoundException;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusinessNotificationService {
    private static final int DEFAULT_LIMIT = 50;
    private final BusinessNotificationRepository repository;
    private final AccessControl accessControl;
    private final Clock clock;

    public BusinessNotificationService(
            BusinessNotificationRepository repository, AccessControl accessControl, Clock clock) {
        this.repository = repository;
        this.accessControl = accessControl;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public BusinessNotificationPage list() {
        SecurityPrincipal principal = accessControl.requireAuthenticated();
        AuthorizedReadScope scope = accessControl.requireReadScope();
        return new BusinessNotificationPage(
                repository.findVisible(scope, principal.subjectId(), DEFAULT_LIMIT),
                repository.countUnread(scope, principal.subjectId()));
    }

    @Transactional
    public BusinessNotification markRead(UUID eventId) {
        SecurityPrincipal principal = accessControl.requireAuthenticated();
        AuthorizedReadScope scope = accessControl.requireReadScope();
        BusinessNotification visible = repository
                .findVisibleById(eventId, scope, principal.subjectId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "NOTIFICATION_NOT_FOUND", "Notification does not exist"));
        repository.markRead(eventId, principal.subjectId(), clock.instant());
        return new BusinessNotification(
                visible.id(), visible.sequence(), visible.aggregateType(), visible.aggregateId(),
                visible.actionCode(), visible.productCode(), visible.surveyYear(), visible.regionCodes(),
                visible.occurredAt(), true);
    }
}
