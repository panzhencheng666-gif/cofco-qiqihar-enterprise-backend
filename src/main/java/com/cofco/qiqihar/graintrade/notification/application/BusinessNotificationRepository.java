package com.cofco.qiqihar.graintrade.notification.application;

import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BusinessNotificationRepository {
    /** Cursor for the shared event stream, independent of the personal inbox scope. */
    long latestSequence();

    List<BusinessNotification> findVisible(
            AuthorizedReadScope scope, String subjectId, int limit);

    List<BusinessNotification> findVisibleAfter(
            AuthorizedReadScope scope, String subjectId, long afterSequence, int limit);

    /** Only shared reporting refresh events; never the notification inbox or identity events. */
    List<BusinessNotification> findReportingChangesAfter(
            String subjectId, long afterSequence, int limit);

    long countUnread(AuthorizedReadScope scope, String subjectId);

    Optional<BusinessNotification> findVisibleById(
            UUID eventId, AuthorizedReadScope scope, String subjectId);

    void markRead(UUID eventId, String subjectId, Instant readAt);
}
