package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IqsSearchRequestTest {
    private static final Instant NOW = Instant.parse("2026-09-28T15:00:00Z");
    private static final IqsSearchRequest.Credentials CREDS =
        new IqsSearchRequest.Credentials("test-id", "test-secret", "test-token", NOW.plusSeconds(300));

    @Test void matchesOfficialAcs3Vector() {
        var headers = Map.of("host", "ecs.cn-shanghai.aliyuncs.com", "x-acs-action", "RunInstances",
            "x-acs-version", "2014-05-26", "x-acs-date", "2023-10-26T10:22:32Z",
            "x-acs-signature-nonce", "3156853299f313e23d1673dc12e1703d",
            "x-acs-content-sha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(IqsSearchRequest.authorization("/", "ImageId=win2019_1809_x64_dtc_zh-cn_40G_alibase_20230811.vhd&RegionId=cn-shanghai", headers, "YourAccessKeyId", "YourAccessKeySecret"))
            .endsWith("Signature=06563a9e1b43f5dfe96b81484da74bceab24a1d853912eee15083a6f0f3283c0");
    }

    @Test void signsStsTokenAndFixedHttpsEndpoint() {
        var request = IqsSearchRequest.create(CREDS, "CNLiteBasic", "粮食", NOW, "nonce-1");
        assertThat(request.uri().toString()).isEqualTo("https://iqs.cn-zhangjiakou.aliyuncs.com/linked-retrieval/linked-retrieval-entry/v1/iqs/search/unified");
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.headers().firstValue("x-acs-security-token")).contains("test-token");
        assertThat(request.headers().firstValue("Authorization").orElseThrow()).contains("x-acs-security-token");
        assertThat(request.toString()).doesNotContain("test-secret", "test-token");
    }

    @Test void credentialLoggingIsRedacted() {
        assertThat(CREDS.toString()).doesNotContain("test-id", "test-secret", "test-token");
    }

    @Test void expiredCredentialsFailBeforeConstructingRequest() {
        var expired = new IqsSearchRequest.Credentials("test-id", "test-secret", "test-token", NOW);
        assertThatThrownBy(() -> IqsSearchRequest.create(expired, "CNLiteBasic", "grain", NOW, "nonce-1"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Expired or nearly expired credentials");
    }

    @Test void unsupportedEnginesAndHeaderInjectionAreRejected() {
        assertThatThrownBy(() -> IqsSearchRequest.create(CREDS, "Generic", "grain", NOW, "nonce-1"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IqsSearchRequest.create(CREDS, "GlobalAdvanced", "grain", NOW, "a\r\nb"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void payloadDisablesExtraContentAndGlobalTimeFilter() {
        assertThat(IqsSearchRequest.payload("GlobalAdvanced", "wheat"))
            .doesNotContain("timeRange").contains("\"summary\":false", "\"mainText\":false");
        assertThat(IqsSearchRequest.payload("CNLiteBasic", "粮食"))
            .contains("\"timeRange\":\"OneDay\"");
        assertThatThrownBy(() -> IqsSearchRequest.payload("CNLiteBasic", " "))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
