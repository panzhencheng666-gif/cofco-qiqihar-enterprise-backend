package com.cofco.qiqihar.riskintelligence.integration;

import java.util.Optional;

interface SourceFactRepository {
    Optional<SourceFactSnapshot> find(SourceFactKey key);

    boolean insert(SourceFactSnapshot snapshot);
}
