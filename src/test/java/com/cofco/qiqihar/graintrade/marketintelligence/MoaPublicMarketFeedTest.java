package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

class MoaPublicMarketFeedTest {
    private static final Instant FETCHED = Instant.parse("2026-09-24T15:00:00Z");

    @Test
    void parsesOnlyOfficialMonitoringLinksAndPublicationDates() throws Exception {
        var html = """
                <li><a href="./202609/t20260924_6488113.htm" target="_blank" title='9月24日：粮价监测'><span class="sj_gztzle">9月24日：粮价监测</span><span class="sj_gztzri">2026-09-24</span></a></li>
                <li><a href="https://example.com/fake" title='fake'><span class="sj_gztzri">2026-09-24</span></a></li>
                """;
        var rows = MoaPublicMarketFeed.parseHeadlines(html, FETCHED);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().url()).isEqualTo("https://scs.moa.gov.cn/jcyj/202609/t20260924_6488113.htm");
    }

    @Test
    void keepsGrainSeriesAndRejectsMalformedIndexValues() throws Exception {
        var json = JsonMapper.builder().build().readTree("""
                {"content":[{"publishDate":"2026-09-24","indexData":[
                  {"indexName":"粮食价格指数","indexValue":109.74},
                  {"indexName":"粮油产品批发价格指数","indexValue":112.49},
                  {"indexName":"未知指数","indexValue":123.4},
                  {"indexName":"食用油价格指数","indexValue":-1}
                ]}]}
                """);
        var rows = MoaPublicMarketFeed.parseQuotes(json, FETCHED);
        assertThat(rows).extracting(MoaPublicMarketFeed.Quote::series).containsExactly("grain", "grain-oil");
        assertThat(rows.getFirst().value()).isEqualByComparingTo("109.74");
        assertThatThrownBy(() -> MoaPublicMarketFeed.parseQuotes(JsonMapper.builder().build().readTree("{}"), FETCHED))
                .hasMessageContaining("no observations");
    }
}
