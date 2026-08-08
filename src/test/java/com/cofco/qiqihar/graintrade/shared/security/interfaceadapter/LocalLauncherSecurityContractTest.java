package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LocalLauncherSecurityContractTest {
    private static final Path START_SCRIPT = Path.of("scripts/start-local.sh");
    private static final Path LISTENER_GUARD = Path.of("scripts/verify-loopback-listener.sh");
    private static final Path OWNERSHIP_SCRIPT = Path.of("scripts/local-process-ownership.sh");
    private static final Path LINK_VERIFIER = Path.of("scripts/verify-local-links.sh");
    private static final Path HEALTHCHECK = Path.of("scripts/healthcheck-local.sh");
    private static final Path REGION_HIERARCHY_VERIFIER = Path.of("scripts/verify-local-region-hierarchy.sh");

    @Test
    void bothViteLaunchesOverrideConfigurationWithNumericLoopback() throws IOException {
        String normalized = java.nio.file.Files.readString(START_SCRIPT)
                .replace('\\', ' ')
                .replaceAll("\\s+", " ");

        assertThat(normalized)
                .doesNotContain("--host 0.0.0.0");
        assertThat(occurrences(normalized, "--host 127.0.0.1")).isEqualTo(2);
        assertThat(normalized).contains("npm run dev", "npm run prototype");
    }

    @Test
    void launcherChecksAllThreeListenersBeforeReuseAndAfterStart() throws IOException {
        String script = java.nio.file.Files.readString(START_SCRIPT);

        assertThat(occurrences(script, "require_loopback_listener \"$backend_port\" \"backend\""))
                .isGreaterThanOrEqualTo(2);
        assertThat(occurrences(script, "require_loopback_listener \"$overview_port\" \"overview frontend\""))
                .isGreaterThanOrEqualTo(2);
        assertThat(occurrences(script, "require_loopback_listener \"$business_port\" \"business frontend\""))
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void listenerGuardAcceptsRealLoopbackSocket() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            ProcessResult result = runListenerGuard(socket.getLocalPort());

            assertThat(result.exitCode()).as(result.output()).isZero();
        }
    }

    @Test
    void listenerGuardRejectsRealWildcardSocket() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("0.0.0.0"))) {
            ProcessResult result = runListenerGuard(socket.getLocalPort());

            assertThat(result.exitCode()).as(result.output()).isNotZero();
            assertThat(result.output()).contains("outside numeric loopback");
        }
    }

    @Test
    void ownedStopNeverEscalatesToAnUnrecoverableSignal() throws IOException {
        String script = java.nio.file.Files.readString(OWNERSHIP_SCRIPT);

        assertThat(script).doesNotContain("kill -9");
        assertThat(script).contains("retaining record");
    }

    @Test
    void ownershipRecordBindsTheRootAndActualListenerBeforeStoppingEither() throws IOException {
        String script = java.nio.file.Files.readString(OWNERSHIP_SCRIPT);

        assertThat(script).contains("owned-v2", "listener_pid", "process_is_same_or_descendant");
        assertThat(script).contains("kill -TERM \"$COFCO_OWNED_LISTENER_PID\"");
        assertThat(script).contains("kill -TERM \"$COFCO_OWNED_ROOT_PID\"");
    }

    @Test
    void legacyScanCoversBothFrontendRuntimeConfigurations() throws IOException {
        String script = java.nio.file.Files.readString(LINK_VERIFIER);

        assertThat(script).contains("overview_frontend_root", "business_frontend_root");
        assertThat(script).contains("${overview_frontend_root}/package.json", "${overview_frontend_root}/vite.config.ts");
        assertThat(script).contains("${business_frontend_root}/package.json", "${business_frontend_root}/vite.prototype.config.ts");
    }

    @Test
    void localReadinessRequiresTheThreePrefecturesAndRepresentativeVillagePaths() throws IOException {
        String hierarchyVerifier = java.nio.file.Files.readString(REGION_HIERARCHY_VERIFIER);
        String startScript = java.nio.file.Files.readString(START_SCRIPT);
        String healthcheck = java.nio.file.Files.readString(HEALTHCHECK);
        String linkVerifier = java.nio.file.Files.readString(LINK_VERIFIER);

        assertThat(hierarchyVerifier)
                .contains("230200", "231100", "150700")
                .contains("231102", "231102101", "231102101001")
                .contains("150721", "150721100", "150721100001")
                .contains("boundaryGeoJson");
        assertThat(startScript).contains("verify-local-region-hierarchy.sh");
        assertThat(healthcheck).contains("verify-local-region-hierarchy.sh");
        assertThat(linkVerifier).contains("verify-local-region-hierarchy.sh");
    }

    private static int occurrences(String text, String fragment) {
        return (text.length() - text.replace(fragment, "").length()) / fragment.length();
    }

    private static ProcessResult runListenerGuard(int port) throws Exception {
        Process process = new ProcessBuilder("bash", LISTENER_GUARD.toString(), Integer.toString(port), "test listener")
                .redirectErrorStream(true)
                .start();
        assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        return new ProcessResult(process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
