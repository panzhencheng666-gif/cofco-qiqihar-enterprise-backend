package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class MoaDepartmentNewsFeedTest {
    @Test
    void acceptsRelevantOfficialDatedHeadlinesOnly() throws Exception {
        var html = """
                <li class="ztlb"><a href="./202609/t20260923_1.htm" title='农业农村部部署秋粮收购'>秋粮收购</a><span> 2026-09-23</span></li>
                <li class="ztlb"><a href="https://fake-moa.gov.cn/story" title='粮食假新闻'>假新闻</a><span> 2026-09-23</span></li>
                <li class="ztlb"><a href="./202609/t20260922_2.htm" title='机关人事任免'>人事</a><span> 2026-09-22</span></li>
                """;
        var items = MoaDepartmentNewsFeed.parse(html, Instant.parse("2026-09-24T00:00:00Z"));
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().url()).isEqualTo("https://www.moa.gov.cn/xw/zwdt/202609/t20260923_1.htm");
    }

    @Test
    void rejectsUndatedOrIrrelevantPage() {
        assertThatThrownBy(() -> MoaDepartmentNewsFeed.parse("<html>粮食</html>", Instant.now()))
                .hasMessageContaining("no relevant dated headlines");
    }
}
