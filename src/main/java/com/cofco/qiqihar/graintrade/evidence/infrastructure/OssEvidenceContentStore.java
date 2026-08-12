package com.cofco.qiqihar.graintrade.evidence.infrastructure;

import com.aliyun.credentials.Client;
import com.aliyun.credentials.models.Config;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.Credentials;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.DefaultCredentials;
import com.aliyun.oss.model.CannedAccessControlList;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.cofco.qiqihar.graintrade.evidence.application.EvidenceContentStore;
import com.cofco.qiqihar.graintrade.evidence.application.EvidenceContentUnavailableException;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "qiqihar.evidence.content.mode", havingValue = "oss")
public class OssEvidenceContentStore implements EvidenceContentStore {
    private static final int MAX_ENVELOPE_BYTES = 40 * 1024 * 1024;
    private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]");
    private static final Pattern PREFIX = Pattern.compile("[a-z0-9][a-z0-9/_-]{0,119}");
    private final OSS client;
    private final String bucket;
    private final String prefix;
    private final String kmsKeyReference;

    public OssEvidenceContentStore(
            @Value("${qiqihar.evidence.content.oss.endpoint:}") String endpoint,
            @Value("${qiqihar.evidence.content.oss.bucket:}") String bucket,
            @Value("${qiqihar.evidence.content.oss.prefix:}") String prefix,
            @Value("${qiqihar.evidence.content.oss.kms-key-reference:}") String kmsKeyReference,
            @Value("${qiqihar.evidence.content.oss.ram-role:}") String ramRole,
            @Value("${qiqihar.evidence.content.oss.imdsv2-required:true}") boolean imdsv2Required) {
        this(buildClient(endpoint, ramRole, imdsv2Required), bucket, prefix, kmsKeyReference);
    }

    OssEvidenceContentStore(OSS client, String bucket, String prefix, String kmsKeyReference) {
        this.client = java.util.Objects.requireNonNull(client);
        if (bucket == null || !BUCKET.matcher(bucket).matches()
                || prefix == null || !PREFIX.matcher(stripTrailingSlash(prefix)).matches()
                || kmsKeyReference == null || kmsKeyReference.isBlank()) {
            throw new IllegalStateException("Private OSS configuration is invalid");
        }
        this.bucket = bucket;
        this.prefix = stripTrailingSlash(prefix) + "/";
        this.kmsKeyReference = kmsKeyReference;
    }

    @Override
    public void put(String key, byte[] envelope) {
        if (envelope == null || envelope.length < 1 || envelope.length > MAX_ENVELOPE_BYTES) {
            throw new IllegalArgumentException("Invalid private evidence content");
        }
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(envelope.length);
        metadata.setContentType("application/octet-stream");
        metadata.setServerSideEncryption(ObjectMetadata.KMS_SERVER_SIDE_ENCRYPTION);
        metadata.setServerSideEncryptionKeyId(kmsKeyReference);
        metadata.setObjectAcl(CannedAccessControlList.Private);
        try {
            client.putObject(new PutObjectRequest(bucket, objectKey(key),
                    new ByteArrayInputStream(envelope.clone()), metadata));
        } catch (RuntimeException exception) {
            throw new EvidenceContentUnavailableException(exception);
        }
    }

    @Override
    public byte[] get(String key) {
        try (var object = client.getObject(bucket, objectKey(key));
                var input = object.getObjectContent()) {
            byte[] bytes = input.readNBytes(MAX_ENVELOPE_BYTES + 1);
            if (bytes.length < 1 || bytes.length > MAX_ENVELOPE_BYTES) {
                throw new IOException("Invalid private object size");
            }
            return bytes;
        } catch (IOException | RuntimeException exception) {
            throw new EvidenceContentUnavailableException(exception);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(bucket, objectKey(key));
        } catch (RuntimeException exception) {
            throw new EvidenceContentUnavailableException(exception);
        }
    }

    @PreDestroy
    void shutdown() {
        client.shutdown();
    }

    private String objectKey(String key) {
        if (key == null || !key.matches(
                "evidence/[0-9a-f]{2}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.evp")) {
            throw new IllegalArgumentException("Invalid private evidence content key");
        }
        return prefix + key;
    }

    private static OSS buildClient(String endpoint, String ramRole, boolean imdsv2Required) {
        try {
            URI uri = URI.create(endpoint == null ? "" : endpoint);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()
                    || ramRole == null || ramRole.isBlank() || !imdsv2Required) {
                throw new IllegalStateException("Private OSS workload identity configuration is invalid");
            }
            Config credentialsConfig = new Config().setType("ecs_ram_role").setRoleName(ramRole)
                    .setEnableIMDSv2(true).setDisableIMDSv1(true).setConnectTimeout(3000).setTimeout(5000);
            return new OSSClientBuilder().build(endpoint, new WorkloadIdentityCredentialsProvider(
                    new Client(credentialsConfig)));
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Private OSS workload identity configuration is invalid", exception);
        }
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static final class WorkloadIdentityCredentialsProvider implements CredentialsProvider {
        private final Client credentials;

        private WorkloadIdentityCredentialsProvider(Client credentials) {
            this.credentials = credentials;
        }

        @Override
        public void setCredentials(Credentials ignored) {
            throw new UnsupportedOperationException("Workload identity credentials cannot be replaced");
        }

        @Override
        public Credentials getCredentials() {
            var value = credentials.getCredential();
            if (value == null || value.getAccessKeyId() == null || value.getAccessKeySecret() == null
                    || value.getSecurityToken() == null) {
                throw new IllegalStateException("Workload identity credentials are unavailable");
            }
            return new DefaultCredentials(value.getAccessKeyId(), value.getAccessKeySecret(),
                    value.getSecurityToken());
        }
    }
}
