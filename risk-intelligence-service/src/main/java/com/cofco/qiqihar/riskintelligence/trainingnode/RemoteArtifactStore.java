package com.cofco.qiqihar.riskintelligence.trainingnode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RemoteArtifactStore {
    private static final String REFERENCE_PREFIX="risk-artifact://sha256/";
    private final Path root;
    private final long maximumBytes;
    private final long maximumTotalBytes;

    @Autowired
    public RemoteArtifactStore(
            @Value("${qiqihar.risk.training.remote-node.artifact-root:var/risk-remote-artifacts}")
            String root,
            @Value("${qiqihar.risk.training.remote-node.maximum-artifact-bytes:67108864}")
            long maximumBytes,
            @Value("${qiqihar.risk.training.remote-node.maximum-total-artifact-bytes:1073741824}")
            long maximumTotalBytes) {
        this.root=Path.of(root).toAbsolutePath().normalize();
        this.maximumBytes=maximumBytes;
        this.maximumTotalBytes=maximumTotalBytes;
    }

    RemoteArtifactStore(String root,long maximumBytes) {
        this(root,maximumBytes,maximumBytes*16);
    }

    public synchronized StoredRemoteArtifact store(UUID trainingRunId,String declaredBundleSha256,
            String contentSha256,byte[] bundle) {
        if (bundle==null) throw new IllegalArgumentException("训练工件包大小不合法");
        return store(trainingRunId,declaredBundleSha256,contentSha256,
                new ByteArrayInputStream(bundle),bundle.length);
    }

    public synchronized StoredRemoteArtifact store(UUID trainingRunId,String declaredBundleSha256,
            String contentSha256,InputStream bundle,long declaredLength) {
        requireHash(declaredBundleSha256,"训练工件包 SHA-256");
        requireHash(contentSha256,"训练工件内容 SHA-256");
        if (trainingRunId==null || bundle==null || declaredLength==0 || declaredLength>maximumBytes) {
            throw new IllegalArgumentException("训练工件包大小不合法");
        }
        Path temporaryDirectory=null;
        try {
            Files.createDirectories(root);
            Path target=root.resolve(declaredBundleSha256+".artifact");
            Path targetBundle=target.resolve("artifact.bundle");
            Path contentHashFile=target.resolve("content-sha256");
            Path runIdFile=target.resolve("training-run-id");
            temporaryDirectory=Files.createTempDirectory(root,".upload-");
            setDirectoryOwnerOnly(temporaryDirectory);
            Path temporary=temporaryDirectory.resolve("artifact.bundle");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            long actualLength=copyBounded(bundle,temporary,digest);
            String actual=HexFormat.of().formatHex(digest.digest());
            if (declaredLength>0 && declaredLength!=actualLength) {
                throw new IllegalArgumentException("训练工件包长度与请求声明不一致");
            }
            if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                    declaredBundleSha256.getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException("训练工件包 SHA-256 与实际内容不一致");
            }
            String actualContentHash=canonicalBundleContentHash(temporary);
            if (!MessageDigest.isEqual(actualContentHash.getBytes(StandardCharsets.US_ASCII),
                    contentSha256.getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException("训练工件内容 SHA-256 与包内模型不一致");
            }
            if (Files.exists(target)) {
                if (!sha256(targetBundle).equals(declaredBundleSha256)
                        || !Files.exists(contentHashFile)
                        || !Files.readString(contentHashFile).strip().equals(contentSha256)
                        || !Files.exists(runIdFile)
                        || !Files.readString(runIdFile).strip().equals(trainingRunId.toString())) {
                    throw new IllegalStateException("相同哈希的训练工件已存在但内容不一致");
                }
            } else {
                if (storedBytes()+actualLength>maximumTotalBytes) {
                    throw new IllegalStateException("训练工件存储已达到安全配额");
                }
                Files.writeString(temporaryDirectory.resolve("content-sha256"),contentSha256+"\n");
                Files.writeString(temporaryDirectory.resolve("training-run-id"),trainingRunId+"\n");
                try (var items=Files.list(temporaryDirectory)) {
                    for (Path item:items.toList()) setOwnerOnly(item);
                }
                Files.move(temporaryDirectory,target,StandardCopyOption.ATOMIC_MOVE);
                temporaryDirectory=null;
            }
            return new StoredRemoteArtifact(
                    REFERENCE_PREFIX+declaredBundleSha256,
                    declaredBundleSha256,contentSha256,actualLength);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("训练工件无法写入云端不可变存储",exception);
        } finally {
            if (temporaryDirectory!=null) deleteTemporary(temporaryDirectory);
        }
    }

    public synchronized boolean matches(
            UUID trainingRunId,String artifactReference,String bundleSha256) {
        if (artifactReference==null || !artifactReference.startsWith(REFERENCE_PREFIX)
                || trainingRunId==null || bundleSha256==null
                || !bundleSha256.matches("[0-9a-f]{64}")) return false;
        String referencedHash=artifactReference.substring(REFERENCE_PREFIX.length());
        if (!bundleSha256.equals(referencedHash)) return false;
        Path target=root.resolve(bundleSha256+".artifact");
        Path bundle=target.resolve("artifact.bundle");
        Path runIdFile=target.resolve("training-run-id");
        try {
            return Files.isRegularFile(bundle) && Files.isRegularFile(runIdFile)
                    && sha256(bundle).equals(bundleSha256)
                    && Files.readString(runIdFile).strip().equals(trainingRunId.toString());
        } catch (java.io.IOException exception) {
            return false;
        }
    }

    private long storedBytes() throws java.io.IOException {
        try (var paths=Files.walk(root)) {
            return paths.filter(path -> path.getFileName().toString().equals("artifact.bundle"))
                    .mapToLong(path -> {
                        try { return Files.size(path); }
                        catch (java.io.IOException exception) { throw new java.io.UncheckedIOException(exception); }
                    }).sum();
        } catch (java.io.UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    private String canonicalBundleContentHash(Path bundle) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        Set<String> paths=new HashSet<>();
        long expandedBytes=0;
        try (var file=Files.newInputStream(bundle);
             var gzip=new GzipCompressorInputStream(file);
             var archive=new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry=archive.getNextEntry())!=null) {
                if (!entry.isFile()) {
                    if (entry.isDirectory()) continue;
                    throw new IllegalArgumentException("训练工件包包含非普通文件");
                }
                String name=Path.of(entry.getName()).normalize().toString().replace('\\','/');
                if (name.startsWith("../") || name.startsWith("/") || !paths.add(name)) {
                    throw new IllegalArgumentException("训练工件包路径不安全或重复");
                }
                digest.update(name.getBytes(StandardCharsets.UTF_8));
                digest.update((byte)0);
                byte[] buffer=new byte[8192];
                int read;
                while ((read=archive.read(buffer))!=-1) {
                    expandedBytes+=read;
                    if (expandedBytes>maximumBytes*4) {
                        throw new IllegalArgumentException("训练工件包解压后超过安全限制");
                    }
                    digest.update(buffer,0,read);
                }
                digest.update((byte)0);
            }
        }
        if (paths.isEmpty()) throw new IllegalArgumentException("训练工件包为空");
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void requireHash(String value,String label) {
        if (value==null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label+" 不合法");
        }
    }

    private long copyBounded(InputStream source,Path target,MessageDigest digest) throws java.io.IOException {
        long total=0;
        byte[] buffer=new byte[8192];
        try (OutputStream output=Files.newOutputStream(target)) {
            int read;
            while ((read=source.read(buffer))!=-1) {
                total+=read;
                if (total>maximumBytes) throw new IllegalArgumentException("训练工件包大小不合法");
                digest.update(buffer,0,read);
                output.write(buffer,0,read);
            }
        }
        if (total==0) throw new IllegalArgumentException("训练工件包大小不合法");
        return total;
    }

    private static String sha256(Path value) throws java.io.IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try (InputStream input=Files.newInputStream(value)) {
                byte[] buffer=new byte[8192];
                int read;
                while ((read=input.read(buffer))!=-1) digest.update(buffer,0,read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            if (exception instanceof java.io.IOException io) throw io;
            throw new IllegalStateException("SHA-256 不可用",exception);
        }
    }

    private static void setOwnerOnly(Path path) throws java.io.IOException {
        try { Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { }
    }

    private static void setDirectoryOwnerOnly(Path path) throws java.io.IOException {
        try { Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------")); }
        catch (UnsupportedOperationException ignored) { }
    }

    private static void deleteTemporary(Path directory) {
        try (var paths=Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }
}
