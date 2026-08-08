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
    void watchTerminationStopsAllOwnedChildrenAndLeavesUnrelatedProcessAlive() throws Exception {
        Fixture fixture = fixture();
        Path capturedPids = temporaryDirectory.resolve("owned-pids");
        Files.createDirectories(capturedPids);
        installNormalOwnedTools(fixture.fakeBin());
        Process unrelated = startHttpServer(freePort(Set.of(
                fixture.backendPort(), fixture.overviewPort(), fixture.businessPort())),
                "127.0.0.1", fixture.documentRoot());
        Map<String, String> environment = fixture.environment();
        environment.put("COFCO_OWNERSHIP_DOCROOT", fixture.documentRoot().toString());
        environment.put("COFCO_OWNERSHIP_CAPTURE_DIR", capturedPids.toString());
        Path launcherLog = temporaryDirectory.resolve("owned-launcher.log");
        Process launcher = start(START_SCRIPT, List.of(), environment, launcherLog);
        List<Long> ownedPids = List.of(
                readPid(capturedPids.resolve("backend.pid")),
                readPid(capturedPids.resolve("overview.pid")),
                readPid(capturedPids.resolve("business.pid")));
        ownedPids.stream()
                .map(ProcessHandle::of)
                .map(handle -> handle.orElseThrow(() -> new AssertionError("owned child exited early")))
                .forEach(testProcesses::add);
        awaitFileOccurrences(launcherLog, "owned listener pid=", 3);

        launcher.destroy();
        assertThat(launcher.waitFor(10, TimeUnit.SECONDS))
                .as("SIGTERM should finish the watch launcher")
                .isTrue();

        awaitNotAlive(ownedPids);
        assertThat(unrelated.isAlive()).as("unrelated test process must remain alive").isTrue();
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

    @Test
    void manualStopTerminatesOwnedParentAndListenerChildAndReleasesThePort() throws Exception {
        Fixture fixture = fixture();
        ManagedListener managed = startManagedListener(fixture.backendPort(), fixture.documentRoot(), false);
        Path pidFile = fixture.runtimeRoot().resolve("pids/backend.pid");

        ProcessResult record = recordOwnership(pidFile, managed, fixture.backendPort(), "backend");
        ProcessResult stop = run(STOP_SCRIPT, List.of(), fixture.environment());

        assertThat(record.exitCode()).as(record.output()).isZero();
        assertThat(stop.exitCode()).as(stop.output()).isZero();
        awaitNotAlive(managed.parentPid());
        awaitNotAlive(managed.listenerPid());
        awaitPortReleased(fixture.backendPort());
        assertThat(pidFile).doesNotExist();
    }

    @Test
    void refusingListenerRetainsCompleteOwnershipRecordAndFailsStop() throws Exception {
        Fixture fixture = fixture();
        ManagedListener managed = startManagedListener(fixture.backendPort(), fixture.documentRoot(), true);
        Path pidFile = fixture.runtimeRoot().resolve("pids/backend.pid");

        ProcessResult record = recordOwnership(pidFile, managed, fixture.backendPort(), "backend");
        ProcessResult stop = run(STOP_SCRIPT, List.of(), fixture.environment());

        assertThat(record.exitCode()).as(record.output()).isZero();
        assertThat(stop.exitCode()).as(stop.output()).isNotZero();
        assertThat(ProcessHandle.of(managed.listenerPid())).isPresent();
        assertThat(pidFile).exists();
        assertThat(Files.readString(pidFile)).startsWith("owned-v2\n")
                .contains(Long.toString(managed.parentPid()))
                .contains(Long.toString(managed.listenerPid()))
                .contains(Integer.toString(fixture.backendPort()));
    }

    @Test
    void replacementListenerIsNeverSignalledAndRetainsOwnershipRecord() throws Exception {
        Fixture fixture = fixture();
        ManagedListener managed = startManagedListener(fixture.backendPort(), fixture.documentRoot(), false);
        Path pidFile = fixture.runtimeRoot().resolve("pids/backend.pid");
        ProcessResult record = recordOwnership(pidFile, managed, fixture.backendPort(), "backend");
        assertThat(record.exitCode()).as(record.output()).isZero();

        ProcessHandle.of(managed.listenerPid()).orElseThrow().destroy();
        awaitNotAlive(managed.listenerPid());
        awaitNotAlive(managed.parentPid());
        Process competitor = startHttpServer(fixture.backendPort(), "127.0.0.1", fixture.documentRoot());

        ProcessResult stop = run(STOP_SCRIPT, List.of(), fixture.environment());

        assertThat(stop.exitCode()).as(stop.output()).isNotZero();
        assertThat(competitor.isAlive()).as("replacement listener must remain untouched").isTrue();
        assertThat(pidFile).exists();
    }

    @Test
    void stopUsesTheExactListenerWhenTheRecordedRootHasAlreadyExited() throws Exception {
        Fixture fixture = fixture();
        Path releaseRoot = temporaryDirectory.resolve("release-root");
        ManagedListener managed = startRootThatCanExitWithLiveListener(
                fixture.backendPort(), fixture.documentRoot(), releaseRoot);
        Path pidFile = fixture.runtimeRoot().resolve("pids/backend.pid");
        ProcessResult record = recordOwnership(pidFile, managed, fixture.backendPort(), "backend");
        assertThat(record.exitCode()).as(record.output()).isZero();

        Files.createFile(releaseRoot);
        awaitNotAlive(managed.parentPid());
        assertThat(ProcessHandle.of(managed.listenerPid())).isPresent();

        ProcessResult stop = run(STOP_SCRIPT, List.of(), fixture.environment());

        assertThat(stop.exitCode()).as(stop.output()).isZero();
        awaitNotAlive(managed.listenerPid());
        awaitPortReleased(fixture.backendPort());
        assertThat(pidFile).doesNotExist();
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

    private ManagedListener startManagedListener(int port, Path documentRoot, boolean ignoreTerm)
            throws Exception {
        Path childPidFile = temporaryDirectory.resolve("listener-child.pid");
        String childCommand = ignoreTerm
                ? "(trap '' TERM; exec python3 -m http.server \"$port\" --bind 127.0.0.1 --directory \"$root\") &"
                : "python3 -m http.server \"$port\" --bind 127.0.0.1 --directory \"$root\" &";
        String command = "set -euo pipefail; port=$1; root=$2; child_file=$3; "
                + childCommand
                + " child=$!; printf '%s\\n' \"$child\" > \"$child_file\"; "
                + "trap 'wait \"$child\"; exit 0' TERM INT; wait \"$child\"";
        Process parent = new ProcessBuilder("bash", "-c", command, "managed-listener",
                Integer.toString(port), documentRoot.toString(), childPidFile.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        testProcesses.add(parent.toHandle());
        long listenerPid = readPid(childPidFile);
        awaitListening(port, parent);
        return new ManagedListener(parent.pid(), listenerPid);
    }

    private ManagedListener startRootThatCanExitWithLiveListener(
            int port, Path documentRoot, Path releaseRoot) throws Exception {
        Path childPidFile = temporaryDirectory.resolve("detached-listener-child.pid");
        String command = "set -euo pipefail; port=$1; root=$2; child_file=$3; release=$4; "
                + "python3 -m http.server \"$port\" --bind 127.0.0.1 --directory \"$root\" & "
                + "child=$!; printf '%s\\n' \"$child\" > \"$child_file\"; "
                + "while [[ ! -f \"$release\" ]]; do sleep 0.02; done";
        Process parent = new ProcessBuilder("bash", "-c", command, "detached-listener",
                Integer.toString(port), documentRoot.toString(), childPidFile.toString(), releaseRoot.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        testProcesses.add(parent.toHandle());
        long listenerPid = readPid(childPidFile);
        awaitListening(port, parent);
        return new ManagedListener(parent.pid(), listenerPid);
    }

    private ProcessResult recordOwnership(Path pidFile, ManagedListener managed, int port, String service)
            throws Exception {
        return runOwnership("record_owned_process " + shellQuote(pidFile) + " "
                + managed.parentPid() + " " + managed.listenerPid() + " " + port + " "
                + shellQuote(service));
    }

    private ProcessResult runOwnership(String command) throws Exception {
        Process process = new ProcessBuilder("bash", "-c",
                "source " + shellQuote(REPOSITORY.resolve("scripts/local-process-ownership.sh"))
                        + "; " + command)
                .directory(REPOSITORY.toFile())
                .redirectErrorStream(true)
                .start();
        testProcesses.add(process.toHandle());
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("ownership command timeout").isTrue();
        return new ProcessResult(process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static String shellQuote(Path value) {
        return shellQuote(value.toString());
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\\"'\\\"'") + "'";
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

    private void installNormalOwnedTools(Path fakeBin) throws IOException {
        Path fakeMaven = fakeBin.resolve("mvn");
        Files.writeString(fakeMaven, """
                #!/usr/bin/env bash
                set -euo pipefail
                printf '%s\\n' "$$" > "$COFCO_OWNERSHIP_CAPTURE_DIR/backend.pid"
                exec python3 -m http.server "$QIQIHAR_SERVER_PORT" \\
                  --bind 127.0.0.1 --directory "$COFCO_OWNERSHIP_DOCROOT"
                """, StandardCharsets.UTF_8);
        Path fakeNpm = fakeBin.resolve("npm");
        Files.writeString(fakeNpm, """
                #!/usr/bin/env bash
                set -euo pipefail
                if [[ "$2" == "dev" ]]; then
                  service="overview"
                else
                  service="business"
                fi
                port=""
                while [[ $# -gt 0 ]]; do
                  if [[ "$1" == "--port" ]]; then
                    port="$2"
                    break
                  fi
                  shift
                done
                printf '%s\\n' "$$" > "$COFCO_OWNERSHIP_CAPTURE_DIR/${service}.pid"
                exec python3 -m http.server "$port" \\
                  --bind 127.0.0.1 --directory "$COFCO_OWNERSHIP_DOCROOT"
                """, StandardCharsets.UTF_8);
        assertThat(fakeMaven.toFile().setExecutable(true)).isTrue();
        assertThat(fakeNpm.toFile().setExecutable(true)).isTrue();
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

    private static void awaitFileOccurrences(Path file, String expected, int count) throws Exception {
        for (int attempt = 0; attempt < 500; attempt++) {
            if (Files.exists(file) && occurrences(Files.readString(file), expected) >= count) {
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("Output did not contain " + count + " occurrences of '"
                + expected + "': " + file);
    }

    private static int occurrences(String text, String expected) {
        return (text.length() - text.replace(expected, "").length()) / expected.length();
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
        for (int attempt = 0; attempt < 500; attempt++) {
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

    private static void awaitNotAlive(List<Long> pids) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (pids.stream().noneMatch(pid -> ProcessHandle.of(pid).isPresent())) {
                return;
            }
            Thread.sleep(20);
        }
        assertThat(pids)
                .as("all exact owned children should be stopped")
                .allMatch(pid -> ProcessHandle.of(pid).isEmpty());
    }

    private static void awaitPortReleased(int port) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            Process probe = new ProcessBuilder("lsof", "-tiTCP:" + port, "-sTCP:LISTEN", "-P", "-n")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (probe.waitFor(2, TimeUnit.SECONDS)
                    && new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8).isBlank()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("Port was not released: " + port);
    }

    private record ManagedListener(long parentPid, long listenerPid) {
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
                    "COFCO_ENTERPRISE_REGION_VERIFY_SCRIPT", "/usr/bin/true",
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
