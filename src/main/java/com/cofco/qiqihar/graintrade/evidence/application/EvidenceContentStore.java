package com.cofco.qiqihar.graintrade.evidence.application;

public interface EvidenceContentStore {
    void put(String key, byte[] envelope);

    byte[] get(String key);

    void delete(String key);
}
