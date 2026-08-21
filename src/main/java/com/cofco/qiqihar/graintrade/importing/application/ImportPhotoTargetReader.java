package com.cofco.qiqihar.graintrade.importing.application;

import java.util.List;
import java.util.UUID;

public interface ImportPhotoTargetReader {
    List<Target> productionTargets(UUID importJobId);

    record Target(int rowNumber, String recordId, String regionCode) {}
}
