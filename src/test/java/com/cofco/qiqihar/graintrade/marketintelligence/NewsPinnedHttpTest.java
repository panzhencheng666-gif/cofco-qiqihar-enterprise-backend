package com.cofco.qiqihar.graintrade.marketintelligence;
import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import javax.net.ssl.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class NewsPinnedHttpTest {
    @TempDir static Path directory;
    static SSLContext trusted;
    HttpsServer server;
    java.util.concurrent.ExecutorService executor;
    @BeforeAll static void certificate() throws Exception {
        var file = directory.resolve("local.p12");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),
            "-genkeypair","-alias","local","-keyalg","RSA","-keysize","2048","-validity","1",
            "-dname","CN=news.example","-ext","SAN=dns:news.example","-storetype","PKCS12",
            "-keystore",file.toString(),"-storepass","test-only-password","-keypass","test-only-password",
            "-noprompt").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertThat(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        var keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(file)) { keys.load(input,"test-only-password".toCharArray()); }
        var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys,"test-only-password".toCharArray());
        var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(keys);
        trusted = SSLContext.getInstance("TLS");
        trusted.init(km.getKeyManagers(),tm.getTrustManagers(),null);
    }
    @BeforeEach void start() throws Exception {
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setHttpsConfigurator(new HttpsConfigurator(trusted));
        executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
    }
    @AfterEach void stop() {
        server.stop(0); executor.shutdownNow();
    }
    NewsPublicTarget.Target target(String host) {
        return new NewsPublicTarget.Target(URI.create("https://" + host + ":" + server.getAddress().getPort() + "/"),
            List.of(InetAddress.getLoopbackAddress()));
    }
    @Test void connectsToPinnedIpWithOriginalHostAndReturnsRedirectWithoutFollowing() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("Host")).startsWith("news.example");
            exchange.getResponseHeaders().set("Location","https://unsafe.example/");
            exchange.sendResponseHeaders(302,-1); exchange.close();
        });
        var result = NewsPinnedHttp.transport(target("news.example"),trusted,Duration.ofSeconds(3),1024);
        assertThat(result.status()).isEqualTo(302);
        assertThat(result.location()).isEqualTo("https://unsafe.example/");
        assertThat(calls).hasValue(1);
    }
    @Test void readsChunkedBodyAndContentType() throws Exception {
        server.createContext("/", exchange -> {
            try {
                exchange.getResponseHeaders().set("Content-Type","text/html; charset=UTF-8");
                exchange.getResponseHeaders().set("Cache-Control","max-age=30");
                exchange.getResponseHeaders().set("Set-Cookie","do-not-retain=test");
                exchange.sendResponseHeaders(200,0);
                exchange.getResponseBody().write("<title>Wheat</title>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } finally { exchange.close(); }
        });
        var result = NewsPinnedHttp.transport(target("news.example"),trusted,Duration.ofSeconds(3),1024);
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.contentType()).isEqualTo("text/html; charset=UTF-8");
        assertThat(result.headers()).containsEntry("cache-control","max-age=30").doesNotContainKey("set-cookie");
        assertThat(new String(result.body(),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("<title>Wheat</title>");
    }
    @Test void rejectsEncodedBodiesInsteadOfUnboundedDecompression() {
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Content-Encoding","gzip");
            exchange.sendResponseHeaders(200,-1); exchange.close();
        });
        assertThatThrownBy(() -> NewsPinnedHttp.transport(target("news.example"),trusted,Duration.ofSeconds(3),1024))
            .isInstanceOf(java.io.IOException.class).hasMessage("News HTTPS request failed");
    }
    @Test void rejectsWrongCertificateHostnameEvenWithTrustedCertificate() {
        assertThatThrownBy(() -> NewsPinnedHttp.transport(target("wrong.example"),trusted,Duration.ofSeconds(3),1024))
            .isInstanceOf(java.io.IOException.class).hasMessage("News HTTPS request failed").hasNoCause();
    }
    @Test void rejectsUntrustedCertificate() {
        assertThatThrownBy(() -> NewsPinnedHttp.transport(target("news.example"),SSLContext.getDefault(),Duration.ofSeconds(3),1024))
            .isInstanceOf(java.io.IOException.class).hasMessage("News HTTPS request failed");
    }
    @Test void rejectsOversizedBody() {
        server.createContext("/", exchange -> {
            try { exchange.sendResponseHeaders(200,2048); exchange.getResponseBody().write(new byte[2048]); }
            finally { exchange.close(); }
        });
        assertThatThrownBy(() -> NewsPinnedHttp.transport(target("news.example"),trusted,Duration.ofSeconds(3),1024))
            .isInstanceOf(java.io.IOException.class).hasMessage("News HTTPS request failed");
    }
    @Test void boundsStalledResponseIncludingBody() {
        server.createContext("/", exchange -> {
            try {
                exchange.sendResponseHeaders(200,0);
                exchange.getResponseBody().write(1); exchange.getResponseBody().flush();
                Thread.sleep(5000);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        long start = System.nanoTime();
        assertThatThrownBy(() -> NewsPinnedHttp.transport(target("news.example"),trusted,Duration.ofMillis(500),1024))
            .isInstanceOf(java.io.IOException.class).hasMessage("News HTTPS request failed");
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(3));
    }
    @Test void publicEntryRejectsForgedPrivateSnapshot() {
        var forged = new NewsPublicTarget.Target(URI.create("https://news.example/"),
            List.of(InetAddress.getLoopbackAddress()));
        assertThatThrownBy(() -> NewsPinnedHttp.send(forged)).isInstanceOf(java.io.IOException.class)
            .hasMessage("Unsafe news target");
    }
}
