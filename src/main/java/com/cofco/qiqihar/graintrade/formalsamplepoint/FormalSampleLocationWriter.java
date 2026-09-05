package com.cofco.qiqihar.graintrade.formalsamplepoint;

import java.util.UUID;

/** Narrow master-data write contract for transactional observation saves. */
public interface FormalSampleLocationWriter {
    void updateLocation(UUID samplePointId, FormalSampleLocationDraft draft);
}
