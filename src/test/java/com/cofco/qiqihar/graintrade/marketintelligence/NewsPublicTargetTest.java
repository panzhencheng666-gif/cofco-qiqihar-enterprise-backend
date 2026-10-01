package com.cofco.qiqihar.graintrade.marketintelligence;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NewsPublicTargetTest {
    static final URI URL = URI.create("https://news.example/story");
    @Test void returnsImmutableNormalizedSnapshotWithoutSecondDnsLookup() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var address = InetAddress.getByName("8.8.8.8");
        var target = NewsPublicTarget.resolve(URI.create("https://NEWS.example/a/../story#x"), host -> {
            calls.incrementAndGet(); assertThat(host).isEqualTo("news.example"); return new InetAddress[]{address};
        });
        assertThat(target.uri()).isEqualTo(URL);
        assertThat(target.addresses()).containsExactly(address);
        assertThatThrownBy(() -> target.addresses().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(calls).hasValue(1);
    }
    @ParameterizedTest @ValueSource(strings={"0.1.2.3","10.1.2.3","100.64.1.2","127.0.0.1",
        "169.254.169.254","172.16.1.1","192.168.1.1","192.0.0.9","192.0.2.1","198.18.0.1",
        "198.51.100.1","203.0.113.1","224.0.0.1","240.0.0.1","::1","fc00::1","fe80::1",
        "2001:db8::1","2002:0808:0808::1","64:ff9b::a00:1"})
    void rejectsNonPublicOrTransitionAddressesEvenMixedWithPublic(String ip) throws Exception {
        var unsafe = InetAddress.getByName(ip);
        var publicIp = InetAddress.getByName("8.8.8.8");
        assertThatThrownBy(() -> NewsPublicTarget.resolve(URL, host -> new InetAddress[]{publicIp, unsafe}))
                .isInstanceOf(java.io.IOException.class).hasMessage("Unsafe news target");
    }
    @Test void acceptsPublicIpv6() throws Exception {
        var address = InetAddress.getByName("2606:4700:4700::1111");
        assertThat(NewsPublicTarget.resolve(URL, host -> new InetAddress[]{address}).addresses()).containsExactly(address);
    }
    @Test void redirectRevalidatesDnsAndRejectsLoopsAndDowngrades() throws Exception {
        var publicIp = InetAddress.getByName("8.8.8.8");
        assertThat(NewsPublicTarget.redirect(URL, "/next", Set.of(URL), host -> new InetAddress[]{publicIp})
                .uri()).isEqualTo(URI.create("https://news.example/next"));
        assertThatThrownBy(() -> NewsPublicTarget.redirect(URL, "#again", Set.of(URL), host -> {
            throw new AssertionError("loop must not resolve");
        })).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> NewsPublicTarget.redirect(URL, "http://news.example/next", Set.of(URL), host -> {
            throw new AssertionError("downgrade must not resolve");
        })).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> NewsPublicTarget.redirect(URL, "https://other.example/a", Set.of(URL),
            host -> new InetAddress[]{InetAddress.getLoopbackAddress()})).isInstanceOf(java.io.IOException.class);
    }
    @Test void emptyOrFailedResolutionFailsClosedWithoutLeakingCause() {
        assertThatThrownBy(() -> NewsPublicTarget.resolve(URL, host -> new InetAddress[0]))
                .isInstanceOf(java.io.IOException.class).hasMessage("Unsafe news target");
        assertThatThrownBy(() -> NewsPublicTarget.resolve(URL, host -> { throw new java.io.IOException("private resolver detail"); }))
                .isInstanceOf(java.io.IOException.class).hasMessage("Unsafe news target").hasNoCause();
    }
}
