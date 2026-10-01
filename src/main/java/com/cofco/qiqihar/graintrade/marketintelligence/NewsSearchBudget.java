package com.cofco.qiqihar.graintrade.marketintelligence;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.Duration;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.io.IOException;
import java.util.Set;
import java.util.Objects;

/** Durable pre-reservation gate. Integration must use one shared, protected ledger. */
final class NewsSearchBudget {
    private static final Object PROCESS_LOCK = new Object();
    private static final ZoneId DAY_ZONE = ZoneId.of("Asia/Shanghai");
    // IQS official list prices, milli-yuan/request, no paid contents add-ons.
    private static final int TOTAL_MILLI_YUAN = 10_000;
    private final Path directory;
    private final Instant deadline;
    private final int dailyLimit;
    private final String manifest;

    /** Explicit provisioning only. Never initialize automatically during application startup. */
    static void initialize(Path path, Instant deadline, int dailyLimitPerEngine) throws IOException {
        String manifest = manifest(deadline, dailyLimitPerEngine);
        var directory = path.toAbsolutePath().normalize();
        Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        // Failure leaves an unusable ledger; do not silently recreate or reset it.
        writeExclusive(directory.resolve("manifest"), manifest);
        syncDirectory(directory);
        syncDirectory(directory.getParent());
    }

    NewsSearchBudget(Path path, Instant deadline, int dailyLimitPerEngine) throws IOException {
        directory = path.toAbsolutePath().normalize();
        this.deadline = deadline;
        dailyLimit = dailyLimitPerEngine;
        manifest = manifest(deadline, dailyLimitPerEngine);
        verifyManifest();
    }

    /** Call immediately before ONE network attempt; there is deliberately no refund API. */
    boolean reserve(String engine, Instant now) throws IOException {
        if (!("CNLiteBasic".equals(engine) || "GlobalAdvanced".equals(engine)))
            throw new IllegalArgumentException("Unsupported search engine");
        Objects.requireNonNull(now, "now");
        if (!now.isBefore(deadline)) return false;
        verifyManifest();
        if (now.isBefore(deadline.minus(Duration.ofDays(7)))) return false;
        // JVM guard prevents OverlappingFileLockException; OS lock coordinates processes.
        synchronized (PROCESS_LOCK) {
            try (var lockChannel = FileChannel.open(directory.resolve("lock"),
                    Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                 var lock = lockChannel.lock()) {
                verifyManifest();
                int spent = 0, daily = 0, sequence = 0;
                Instant latest = Instant.MIN;
                var day = now.atZone(DAY_ZONE).toLocalDate();
                while (Files.exists(directory.resolve("request-" + (sequence + 1) + ".reserved"), LinkOption.NOFOLLOW_LINKS)) {
                    var path = directory.resolve("request-" + (++sequence) + ".reserved");
                    if (sequence > 1250 || Files.isSymbolicLink(path) || Files.size(path) > 256)
                        throw new IOException("Invalid budget journal");
                    try {
                        String[] entry = Files.readString(path).strip().split("\\|", -1);
                        if (entry.length != 3) throw new IllegalArgumentException();
                        Instant at = Instant.parse(entry[0]);
                        int price = price(entry[1]);
                        if (Integer.parseInt(entry[2]) != price || at.isBefore(latest)
                                || at.isBefore(deadline.minus(Duration.ofDays(7))) || !at.isBefore(deadline))
                            throw new IllegalArgumentException();
                        latest = at;
                        spent = Math.addExact(spent, price);
                        if (entry[1].equals(engine) && at.atZone(DAY_ZONE).toLocalDate().equals(day)) daily++;
                    } catch (RuntimeException invalid) { throw new IOException("Invalid budget journal", invalid); }
                }
                if (now.isBefore(latest) || daily >= dailyLimit || spent + price(engine) > TOTAL_MILLI_YUAN) return false;
                writeExclusive(directory.resolve("request-" + (sequence + 1) + ".reserved"),
                    now + "|" + engine + "|" + price(engine) + "\n");
                syncDirectory(directory);
                return true;
            }
        }
    }

    private static int price(String engine) {
        return switch (engine) {
            case "CNLiteBasic" -> 8;
            case "GlobalAdvanced" -> 56;
            default -> throw new IllegalArgumentException("Unsupported search engine");
        };
    }

    boolean beforeDeadline(Instant now) {
        return Objects.requireNonNull(now, "now").isBefore(deadline)
            && !now.isBefore(deadline.minus(Duration.ofDays(7)));
    }

    private void verifyManifest() throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS)
                    .equals(PosixFilePermissions.fromString("rwx------")))
            throw new IOException("Unsafe budget directory");
        var path = directory.resolve("manifest");
        try (var channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() > 1024) throw new IOException("Invalid budget manifest");
            var buffer = ByteBuffer.allocate(1024);
            while (channel.read(buffer) > 0) { }
            buffer.flip();
            if (!StandardCharsets.UTF_8.decode(buffer).toString().equals(manifest))
                throw new IOException("Budget configuration mismatch");
        }
    }

    private static String manifest(Instant deadline, int remaining) {
        Objects.requireNonNull(deadline, "deadline");
        if (remaining < 0 || remaining > 100) throw new IllegalArgumentException("Invalid search allowance");
        return "qiliang-search-budget-v2\n" + deadline + "\n" + remaining
            + "\nAsia/Shanghai\n10000\nCNLiteBasic=8\nGlobalAdvanced=56\n";
    }

    private static void writeExclusive(Path path, String content) throws IOException {
        try (var channel = FileChannel.open(path,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            var bytes = StandardCharsets.UTF_8.encode(content);
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
    }

    private static void syncDirectory(Path directory) throws IOException {
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }
}
