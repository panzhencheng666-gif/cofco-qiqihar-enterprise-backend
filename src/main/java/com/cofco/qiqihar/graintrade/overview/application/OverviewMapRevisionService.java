package com.cofco.qiqihar.graintrade.overview.application;

import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import org.springframework.stereotype.Service;

@Service
public class OverviewMapRevisionService {
    private final AccessControl access;
    private final OverviewMapRevisionRepository repository;
    public OverviewMapRevisionService(AccessControl access, OverviewMapRevisionRepository repository) {
        this.access = access;
        this.repository = repository;
    }
    public String currentRevision() {
        access.requireOverviewReadScope();
        return repository.currentRevision();
    }
}
