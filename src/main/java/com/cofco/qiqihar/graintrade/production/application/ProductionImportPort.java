package com.cofco.qiqihar.graintrade.production.application;

/** Explicit production-module boundary used by the shared import workflow. */
public interface ProductionImportPort {
    String importDraft(ProductionDraft draft);
}
