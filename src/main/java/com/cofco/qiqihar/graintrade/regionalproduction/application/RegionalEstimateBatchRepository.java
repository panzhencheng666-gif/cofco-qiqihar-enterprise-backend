package com.cofco.qiqihar.graintrade.regionalproduction.application;

import java.util.Optional;

public interface RegionalEstimateBatchRepository {
    Optional<RegionalEstimateBatch> latest(String root, int year);
    void save(RegionalEstimateBatch batch, String inputHash);
    Optional<String> latestHash(String root, int year);
}
