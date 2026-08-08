package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalLauncherOwnershipIntegrationTest {
    private static final Path REPOSITORY = Path.of("").toAbsolutePath();
    private static final Path START_SCRIPT = REPOSITORY.resolve("scripts/start-local.sh");
    private static final Path STOP_SCRIPT = REPOSITORY.resolve("scripts/stop-local.sh");
    private final List<ProcessHandle> testProcesses = new ArrayList<>();

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void stopOnlyProcessesCreatedByTheTest() {
        testProcesses.forEach(process -> {
            if (process.isAlive()) {
                process.destroy();
            }
        });
        testProcesses.forEach(process -> {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        });
    }

    @Test
    void attachedListenersSurviveLauncherAndManualStop() throws Exception {
        Fixture fixture = fixture();
        Process backend = startHttpServer(fixture.backendPort(), "127.0.0.1", fixture.documentRoot());
        Process overview = startHttpServer(fixture.overviewPort(), "127.0.0.1", fixture.documentRoot());
        Process business = startHttpServer(fixture.businessPort(), "127.0.0.1", fixture.documentRoot());

        ProcessResult launch = run(START_SCRIPT, List.of("--no-watch"), fixture.environment());
        ProcessResult stop = run(STOP_SCRIPT, List.of(), fixture.environment());

        assertThat(launch.exitCode()).as(launch.output()).isZero();
        assertThat(stop.exitCode()).as(stop.output()).isZero();
        assertThat(backend.isAlive()).as("attached backend must remain alive").isTrue();
        assertThat(overview.isAlive()).as("attached overview must remain alive").isTrue();
        assertThat(business.isAlive()).as("attached business must remain alive").isTrue();
        try (var pidFiles = Files.list(fixture.runtimeRoot().resolve("pids"))) {
            assertThat(pidFiles.toList()).isEmpty();
        }
    }

    @Test
    void attachedListenersSurviveLauncherTerminationAndSubsequentManualStop() throws Exception {
        Fixture fixture = fixture();
        Process backend = startHttpServer(fixture.backendPort(), "127.0.0.1", fixture.documentRoot());
        Process overview = startHttpServer(fixture.overviewPort(), "127.0.0.1", fixture.documentRoot());
        Process business = startHttpServer(fixture.businessPort(), "127.0.0.1", fixture.documentRoot());
        Path launcherLog = temporaryDirectory.resolve("launcher.log");
        Process launcher = start(START_SCRIPT, List.of(), fixture.environment(), launcherLog);
        awaitFileContains(launcherLog, "Started services.");

        launcher.destroy();
        assertThat(launcher.waitFor(10, TimeUnit.SECONDS))
                .as("SIGTERM should finish the watch launcher after safe cleanup")
                .isTrue();
        ProcessResult stop = run(STOP_SCRIPT, List.of(), fixture.environment());

        assertThat(stop.exitCode()).as(stop.output()).isZero();
        assertThat(backend.isAlive()).as("attached backend must remain alive").isTrue();
        assertThat(overview.isAlive()).as("attached overview must remain alive").isTrue();
        assertThat(business.isAlive()).as("attached business must remain alive").isTrue();
    }

    @Test
    void exitedOwnedChildCannotCauseCompetingListenerToBeKilled() throws Exception {
        Fixture fixture = fixture();
        startHttpServer(fixture.overviewPort(), "127.0.0.1", fixture.documentRoot());
        startHttpServer(fixture.businessPort(), "127.0.0.1", fixture.documentRoot());
        Path competitorPidFile = temporaryDirectory.resolve("competitor.pid");
        Path capturedPidFile = temporaryDirectory.resolve("captured.pid");
        installFakeMaven(fixture.fakeBin(), "competitor");
        Map<String, String> environment = fixture.environment();
        environment.put("COFCO_OWNERSHIP_TEST_MODE", "competitor");
        environment.put("COFCO_OWNERSHIP_DOCROOT", fixture.documentRoot().toString());
        environment.put("COFCO_OWNERSHIP_COMPETITOR_PID_FILE", competitorPidFile.toString());
        environment.put("COFCO_OWNERSHIP_CAPTURED_PID_FILE", capturedPidFile.toString());

        ProcessResult launch = run(START_SCRIPT, List.of("--no-watch"), environment);
        long capturedPid = readPid(capturedPidFile);
        long competitorPid = readPid(competitorPidFile);
        java.util.Optional<ProcessHandle> competitorHandle = ProcessHandle.of(competitorPid);
        assertThat(competitorHandle)
                .as("a competing listener must not be killed through a port re-query")
                .isPresent();
        ProcessHandle competitor = competitorHandle.orElseThrow();
        testProcesses.add(competitor);

        assertThat(launch.exitCode()).as(launch.output()).isNotZero();
        assertThat(ProcessHandle.of(capturedPid)).isEmpty();
        assertThat(competitor.isAlive())
                .as("a competing listener must not be killed through a port re-query")
                .isTrue();
    }

    @Test
    void failedValidationStopsOnlyTheExactOwnedChild() throws Exception {
        Fixture fixture = fixture();
        startHttpServer(fixture.overviewPort(), "127.0.0.1", fixture.documentRoot());
        startHttpServer(fixture.businessPort(), "127.0.0.1", fixture.documentRoot());
        Process unrelated = startHttpServer(freePort(Set.of(
                fixture.backendPort(), fixture.overviewPort(), fixture.businessPort())),
                "127.0.0.1", fixture.documentRoot());
        Path capturedPidFile = temporaryDirectory.resolve("captured.pid");
        installFakeMaven(fixture.fakeBin(), "owned");
        Map<String, String> environment = fixture.environment();
        environment.put("COFCO_OWNERSHIP_TEST_MODE", "owned");
        environment.put("COFCO_OWNERSHIP_DOCROOT", fixture.documentRoot().toString());
        environment.put("COFCO_OWNERSHIP_CAPTURED_PID_FILE", capturedPidFile.toString());

        ProcessResult launch = run(START_SCRIPT, List.of("--no-watch"), environment);
        long capturedPid = readPid(capturedPidFile);

        assertThat(launch.exitCode()).as(launch.output()).isNotZero();
        awaitNotAlive(capturedPid);
        assertThat(unrelated.isAlive()).as("unrelated test process must remain alive").isTrue();
    }

    private Fixture fixture() throws Exception {
        Set<Integer> usedPorts = new HashSet<>();
        int backendPort = freePort(usedPorts);
        usedPorts.add(backendPort);
        int overviewPort = freePort(usedPorts);
        usedPorts.add(overviewPort);
        int businessPort = freePort(usedPorts);
        Path runtimeRoot = temporaryDirectory.resolve("runtime");
        Path documentRoot = temporaryDirectory.resolve("www");
        Path fakeBin = temporaryDirectory.resolve("fake-bin");
        Files.createDirectories(runtimeRoot.resolve("pids"));
        Files.createDirectories(documentRoot.resolve("actuator"));
        Files.createDirectories(fakeBin);
        Files.writeString(documentRoot.resolve("actuator/health"), "UP", StandardCharsets.UTF_8);
        Files.writeString(documentRoot.resolve("prototype.html"), "UP", StandardCharsets.UTF_8);
        Files.writeString(documentRoot.resolve("index.html"), "UP", StandardCharsets.UTF_8);
        return new Fixture(backendPort, overviewPort, businessPort, runtimeRoot, documentRoot, fakeBin);
    }

    private Process startHttpServer(int port, String bindAddress, Path documentRoot) throws Exception {
        Process process = new ProcessBuilder(
                "python3", "-m", "http.server", Integer.toString(port),
                "--bind", bindAddress, "--directory", documentRoot.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        testProcesses.add(process.toHandle());
        awaitListening(port, process);
        return process;
    }

    private void installFakeMaven(Path fakeBin, String mode) throws IOException {
        Path fakeMaven = fakeBin.resolve("mvn");
        Files.writeString(fakeMaven, """
                #!/usr/bin/env bash
                set -euo pipefail
                printf '%%s\\n' "$$" > "$COFCO_OWNERSHIP_CAPTURED_PID_FILE"
                if [[ "%s" == "competitor" ]]; then
                  nohup python3 -m http.server "$QIQIHAR_SERVER_PORT" \\
                    --bind 0.0.0.0 --directory "$COFCO_OWNERSHIP_DOCROOT" \\
                    </dev/null >/dev/null 2>&1 &
                  printf '%%s\\n' "$!" > "$COFCO_OWNERSHIP_COMPETITOR_PID_FILE"
                  exit 0
                fi
                exec python3 -m http.server "$QIQIHAR_SERVER_PORT" \\
                  --bind 0.0.0.0 --directory "$COFCO_OWNERSHIP_DOCROOT"
                """.formatted(mode), StandardCharsets.UTF_8);
        assertThat(fakeMaven.toFile().setExecutable(true)).isTrue();
    }

    private ProcessResult run(Path script, List<String> arguments, Map<String, String> environment)
            throws Exception {
        List<String> command = new ArrayList<>();
        command.add("bash");
        command.add(script.toString());
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(REPOSITORY.toFile())
                .redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        testProcesses.add(process.toHandle());
        assertThat(process.waitFor(40, TimeUnit.SECONDS)).as("launcher timeout").isTrue();
        return new ProcessResult(process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private Process start(
            Path script, List<String> arguments, Map<String, String> environment, Path output)
            throws IOException {
        List<String> command = new ArrayList<>();
        command.add("bash");
        command.add(script.toString());
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(REPOSITORY.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile());
        builder.environment().putAll(environment);
        Process process = builder.start();
        testProcesses.add(process.toHandle());
        return process;
    }

    private static void awaitFileContains(Path file, String expected) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (Files.exists(file) && Files.readString(file).contains(expected)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("Output did not contain '" + expected + "': " + file);
    }

    private static int freePort(Set<Integer> excluded) throws IOException {
        for (int attempt = 0; attempt < 20; attempt++) {
            try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
                int port = socket.getLocalPort();
                if (!excluded.contains(port) && port != 8090 && port != 63182 && port != 63200) {
                    return port;
                }
            }
        }
        throw new IOException("Unable to allocate an isolated test port");
    }

    private static void awaitListening(int port, Process process) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (!process.isAlive()) {
                throw new IllegalStateException("HTTP test process exited before listening");
            }
            try (java.net.Socket ignored = new java.net.Socket("127.0.0.1", port)) {
                return;
            } catch (IOException ignored) {
                Thread.sleep(20);
            }
        }
        throw new IllegalStateException("HTTP test process did not start on port " + port);
    }

    private static long readPid(Path pidFile) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (Files.exists(pidFile)) {
                return Long.parseLong(Files.readString(pidFile).trim());
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("PID file was not created: " + pidFile);
    }

    private static void awaitNotAlive(long pid) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (ProcessHandle.of(pid).isEmpty()) {
                return;
            }
            Thread.sleep(20);
        }
        assertThat(ProcessHandle.of(pid)).isEmpty();
    }

    private record Fixture(
            int backendPort,
            int overviewPort,
            int businessPort,
            Path runtimeRoot,
            Path documentRoot,
            Path fakeBin) {
        Map<String, String> environment() {
            return new java.util.HashMap<>(Map.of(
                    "COFCO_ENTERPRISE_BACKEND_PORT", Integer.toString(backendPort),
                    "COFCO_ENTERPRISE_OVERVIEW_PORT", Integer.toString(overviewPort),
                    "COFCO_ENTERPRISE_BUSINESS_PORT", Integer.toString(businessPort),
                    "COFCO_ENTERPRISE_LOCAL_RUNTIME_ROOT", runtimeRoot.toString(),
                    "COFCO_ENTERPRISE_FRONTEND_ROOT", temporaryDirectoryStaticWorkaround(),
                    "COFCO_ENTERPRISE_WEB_ROOT", temporaryDirectoryStaticWorkaround(),
                    "JAVA_HOME", "/opt/homebrew/opt/openjdk@21",
                    "PATH", fakeBin + ":" + System.getenv("PATH")));
        }

        private String temporaryDirectoryStaticWorkaround() {
            return documentRoot.toString();
        }
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
