package com.cofco.qiqihar.graintrade.evidence.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.CannedAccessControlList;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.PutObjectRequest;
import com.cofco.qiqihar.graintrade.evidence.application.EvidenceContentUnavailableException;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OssEvidenceContentStoreTest {
    private static final String KEY = "evidence/12/123e4567-e89b-12d3-a456-426614174000.evp";

    @Test
    void usesPrivateKmsEncryptedObjectUnderTheConfiguredPrefix() throws Exception {
        OSS client = mock(OSS.class);
        var store = new OssEvidenceContentStore(client, "cofco-private-preprod", "stage7/evidence",
                "kms-key-reference");
        byte[] content = {1, 2, 3};

        store.put(KEY, content);

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture());
        assertThat(request.getValue().getBucketName()).isEqualTo("cofco-private-preprod");
        assertThat(request.getValue().getKey()).isEqualTo("stage7/evidence/" + KEY);
        assertThat(request.getValue().getMetadata().getContentLength()).isEqualTo(3);
        assertThat(request.getValue().getMetadata().getContentType()).isEqualTo("application/octet-stream");
        assertThat(request.getValue().getMetadata().getServerSideEncryption()).isEqualTo("KMS");
        assertThat(request.getValue().getMetadata().getServerSideEncryptionKeyId())
                .isEqualTo("kms-key-reference");
        assertThat(request.getValue().getMetadata().getRawMetadata().get("x-oss-object-acl"))
                .isEqualTo(CannedAccessControlList.Private.toString());
        assertThat(request.getValue().getInputStream().readAllBytes()).containsExactly(1, 2, 3);
    }

    @Test
    void readsAndDeletesOnlyThePrefixedPrivateObject() throws Exception {
        OSS client = mock(OSS.class);
        OSSObject object = new OSSObject();
        object.setObjectContent(new ByteArrayInputStream(new byte[] {4, 5, 6}));
        when(client.getObject("cofco-private-preprod", "stage7/evidence/" + KEY)).thenReturn(object);
        var store = new OssEvidenceContentStore(client, "cofco-private-preprod", "stage7/evidence/",
                "kms-key-reference");

        assertThat(store.get(KEY)).containsExactly(4, 5, 6);
        store.delete(KEY);

        verify(client).deleteObject("cofco-private-preprod", "stage7/evidence/" + KEY);
    }

    @Test
    void translatesSdkFailuresWithoutLeakingEndpointBucketOrKey() {
        OSS client = mock(OSS.class);
        when(client.putObject(any(PutObjectRequest.class))).thenThrow(new IllegalStateException("sensitive"));
        var store = new OssEvidenceContentStore(client, "cofco-private-preprod", "stage7/evidence",
                "kms-key-reference");

        assertThatThrownBy(() -> store.put(KEY, new byte[] {1}))
                .isInstanceOf(EvidenceContentUnavailableException.class)
                .hasMessage("Private evidence content is temporarily unavailable")
                .message().doesNotContain("sensitive", "cofco-private-preprod", KEY);
    }

    @Test
    void rejectsInsecureOrIncompleteConfigurationBeforeBuildingAClient() {
        OSS client = mock(OSS.class);

        assertThatThrownBy(() -> new OssEvidenceContentStore(client, "Bad_Bucket", "stage7", "kms"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new OssEvidenceContentStore(client, "valid-bucket", "../stage7", "kms"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new OssEvidenceContentStore(client, "valid-bucket", "stage7", ""))
                .isInstanceOf(IllegalStateException.class);
    }
}
