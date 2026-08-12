package com.cofco.qiqihar.graintrade.evidence.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class EvidenceContentEnvelopeTest {
    @Test
    void roundTripsBothPrivateRepresentationsAndMediaType() {
        byte[] original = {1, 2, 3, 4};
        byte[] watermarked = {5, 6, 7};

        byte[] encoded = EvidenceContentEnvelope.encode("image/png", original, watermarked);
        var decoded = EvidenceContentEnvelope.decode(encoded);

        assertThat(decoded.mediaType()).isEqualTo("image/png");
        assertThat(decoded.original()).containsExactly(original);
        assertThat(decoded.watermarked()).containsExactly(watermarked);

        original[0] = 99;
        watermarked[0] = 99;
        encoded[0] = 99;
        assertThat(decoded.original()).containsExactly(1, 2, 3, 4);
        assertThat(decoded.watermarked()).containsExactly(5, 6, 7);
    }

    @Test
    void rejectsTruncationAndTampering() {
        byte[] encoded = EvidenceContentEnvelope.encode("image/jpeg", new byte[] {1, 2}, new byte[] {3, 4});
        byte[] truncated = Arrays.copyOf(encoded, encoded.length - 1);
        byte[] tampered = encoded.clone();
        tampered[tampered.length - 1] ^= 1;

        assertThatThrownBy(() -> EvidenceContentEnvelope.decode(truncated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid evidence content envelope");
        assertThatThrownBy(() -> EvidenceContentEnvelope.decode(tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid evidence content envelope");
    }

    @Test
    void rejectsUnsupportedMediaTypeAndEmptyContent() {
        assertThatThrownBy(() -> EvidenceContentEnvelope.encode("text/plain", new byte[] {1}, new byte[] {2}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvidenceContentEnvelope.encode("image/png", new byte[0], new byte[] {2}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvidenceContentEnvelope.encode("image/png", new byte[] {1}, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
