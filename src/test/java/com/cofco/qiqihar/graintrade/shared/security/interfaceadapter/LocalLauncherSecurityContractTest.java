package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalLauncherSecurityContractTest {
    private static final Path START_SCRIPT = Path.of("scripts/start-local.sh");
    private static final Path LISTENER_GUARD = Path.of("scripts/verify-loopback-listener.sh");
    private static final Path OWNERSHIP_SCRIPT = Path.of("scripts/local-process-ownership.sh");
    private static final Path LINK_VERIFIER = Path.of("scripts/verify-local-links.sh");
    private static final Path HEALTHCHECK = Path.of("scripts/healthcheck-local.sh");
    private static final Path RUNTIME_MANAGER = Path.of("scripts/local-runtime.sh");
    private static final Path REGION_HIERARCHY_VERIFIER = Path.of("scripts/verify-local-region-hierarchy.sh");
    private static final Path REAL_LSOF = Path.of("/usr/sbin/lsof");

    @TempDir
    Path tempDir;

    @Test
    void bothViteLaunchesOverrideConfigurationWithNumericLoopback() throws IOException {
        String normalized = java.nio.file.Files.readString(START_SCRIPT)
                .replace('\\', ' ')
                .replaceAll("\\s+", " ");

        assertThat(normalized)
                .doesNotContain("--host 0.0.0.0");
        assertThat(occurrences(normalized, "--host 127.0.0.1")).isEqualTo(2);
        assertThat(occurrences(normalized, "npm run dev")).isEqualTo(2);
        assertThat(normalized).doesNotContain("npm run prototype");
    }

    @Test
    void localBackendSeparatesRuntimeRegistrarAndMigrationDatabaseIdentities() throws IOException {
        String launcher = java.nio.file.Files.readString(START_SCRIPT);

        assertThat(launcher)
                .contains("runtime_database_user=\"${QIQIHAR_DB_USERNAME:-cofco_app}\"")
                .contains("migration_database_user=\"${QIQIHAR_FLYWAY_USERNAME:-${USER}}\"")
                .contains("event_consumer_registrar_database_user="
                        + "\"${QIQIHAR_EVENT_CONSUMER_REGISTRAR_DB_USERNAME:-"
                        + "qiqihar_event_consumer_registrar_login}\"")
                .contains("\"QIQIHAR_DB_USERNAME=$runtime_database_user\"")
                .contains("\"QIQIHAR_FLYWAY_USERNAME=$migration_database_user\"")
                .contains("\"QIQIHAR_EVENT_CONSUMER_REGISTRAR_DB_USERNAME="
                        + "$event_consumer_registrar_database_user\"");
    }

    @Test
    void businessRuntimeUsesOnlyTheCanonicalRootEntry() throws IOException {
        String runtime = String.join("\n",
                java.nio.file.Files.readString(START_SCRIPT),
                java.nio.file.Files.readString(Path.of("scripts/local-runtime.sh")),
                java.nio.file.Files.readString(HEALTHCHECK),
                java.nio.file.Files.readString(LINK_VERIFIER),
                java.nio.file.Files.readString(Path.of("LOCAL_RUNBOOK.md")),
                java.nio.file.Files.readString(Path.of("docs/operations/local-launchd-runtime.md")));

        assertThat(runtime).doesNotContain("prototype.html", "64185", "vite.prototype.config.ts");
        assertThat(runtime).doesNotContain("/Users/federal/Desktop/cofco-qiqihar-enterprise-backend");
        assertThat(runtime).contains("http://127.0.0.1:${business_port}/");
    }

    @Test
    void runtimeStatusFollowsOnlyBoundedRedirectsForInternalComponents() throws IOException {
        String manager = java.nio.file.Files.readString(RUNTIME_MANAGER);

        assertThat(manager).contains("--location", "--max-redirs 3");
    }

    @Test
    void desktopLauncherContractRequiresTheFormalRuntimeRepository() throws IOException {
        String verifier = java.nio.file.Files.readString(Path.of("scripts/verify-desktop-launcher-contract.sh"));

        assertThat(verifier)
                .contains("/Users/federal/Library/Application Support/COFCO Qiqihar Enterprise/runtime/cofco-qiqihar-enterprise-backend")
                .contains("prototype.html", "64185")
                .contains("/Users/federal/Desktop/cofco-qiqihar-enterprise-backend");
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
    void listenerGuardRetriesTransientEmptyDiscoveryForRealLoopbackSocket() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            ControlledProbeResult result = runListenerGuardWithFirstEmptyDiscovery(
                    socket.getLocalPort(), "loopback");

            assertThat(result.exitCode()).as(result.output()).isZero();
            assertThat(result.invocationCount()).isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void listenerGuardAfterTransientEmptyDiscoveryStillRejectsWildcardSocket() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("0.0.0.0"))) {
            ControlledProbeResult result = runListenerGuardWithFirstEmptyDiscovery(
                    socket.getLocalPort(), "wildcard");

            assertThat(result.exitCode()).as(result.output()).isNotZero();
            assertThat(result.output()).contains("outside numeric loopback");
            assertThat(result.invocationCount()).isGreaterThanOrEqualTo(2);
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
        assertThat(script).contains("${business_frontend_root}/package.json", "${business_frontend_root}/vite.config.ts");
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

    private ControlledProbeResult runListenerGuardWithFirstEmptyDiscovery(int port, String scenario)
            throws Exception {
        assertThat(Files.isExecutable(REAL_LSOF)).isTrue();
        Path fixtureDir = Files.createDirectories(tempDir.resolve(scenario));
        Path marker = fixtureDir.resolve("first-query-completed");
        Path invocationLog = fixtureDir.resolve("invocations.log");
        Path fixtureLsof = fixtureDir.resolve("lsof");
        Files.writeString(fixtureLsof, """
                #!/bin/bash
                set -euo pipefail
                printf '%s\\n' "$*" >> "${COFCO_LSOF_INVOCATION_LOG:?}"
                if [[ ! -e "${COFCO_LSOF_FIRST_EMPTY_MARKER:?}" ]]; then
                  : > "${COFCO_LSOF_FIRST_EMPTY_MARKER}"
                  exit 0
                fi
                exec "${COFCO_REAL_LSOF:?}" "$@"
                """);
        Files.setPosixFilePermissions(fixtureLsof, PosixFilePermissions.fromString("rwx------"));

        ProcessBuilder builder = new ProcessBuilder(
                "bash", LISTENER_GUARD.toAbsolutePath().toString(), Integer.toString(port), "test listener")
                .redirectErrorStream(true);
        builder.environment().put("PATH", fixtureDir.toAbsolutePath()
                + ":/usr/bin:/bin:/usr/sbin:/sbin");
        builder.environment().put("COFCO_LSOF_FIRST_EMPTY_MARKER", marker.toAbsolutePath().toString());
        builder.environment().put("COFCO_LSOF_INVOCATION_LOG", invocationLog.toAbsolutePath().toString());
        builder.environment().put("COFCO_REAL_LSOF", REAL_LSOF.toAbsolutePath().toString());

        System.out.printf("deterministic-listener-probe scenario=%s port=%d fixture=%s%n",
                scenario, port, fixtureLsof.toAbsolutePath());
        Process process = builder.start();
        assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int invocationCount = Files.readAllLines(invocationLog).size();
        return new ControlledProbeResult(process.exitValue(),
                output + "fixture invocation count=" + invocationCount, invocationCount);
    }

    private record ProcessResult(int exitCode, String output) {
    }

    private record ControlledProbeResult(int exitCode, String output, int invocationCount) {
    }
}
