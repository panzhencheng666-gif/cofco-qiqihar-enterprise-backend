package com.cofco.qiqihar.graintrade.shared.application;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PagedResultTest {

    @Test
    void snapshotsContentAndCalculatesThePageCount() {
        List<String> mutableItems = new java.util.ArrayList<>(List.of("a", "b"));

        PagedResult<String> result = new PagedResult<>(mutableItems, 2, 2, 5);
        mutableItems.add("c");

        assertThat(result.items()).containsExactly("a", "b");
        assertThat(result.totalPages()).isEqualTo(3);
    }

    @Test
    void rejectsInvalidPagingMetadata() {
        assertThatThrownBy(() -> new PagedResult<>(List.of(), -1, 20, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PagedResult<>(List.of(), 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PagedResult<>(List.of(), 0, 20, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
