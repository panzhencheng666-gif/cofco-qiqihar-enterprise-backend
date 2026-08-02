package com.cofco.qiqihar.graintrade.workflow.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkItemScopeTest {

    @Test
    void pendingScopeAcceptsOnlyPendingStatuses() {
        assertThat(WorkItemScope.PENDING.accepts(WorkItemStatus.TO_FILL)).isTrue();
        assertThat(WorkItemScope.PENDING.accepts(WorkItemStatus.TO_REVIEW)).isTrue();
        assertThat(WorkItemScope.PENDING.accepts(WorkItemStatus.RETURNED)).isTrue();
        assertThat(WorkItemScope.PENDING.accepts(WorkItemStatus.EXCEPTION)).isTrue();
        assertThat(WorkItemScope.COMPLETED.accepts(WorkItemStatus.TO_FILL)).isFalse();
    }

    @Test
    void completedScopeRejectsAStatusFilter() {
        assertThatThrownBy(() -> WorkItemQuery.of(
                        WorkItemScope.COMPLETED, "TO_FILL", null, null, null, 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
