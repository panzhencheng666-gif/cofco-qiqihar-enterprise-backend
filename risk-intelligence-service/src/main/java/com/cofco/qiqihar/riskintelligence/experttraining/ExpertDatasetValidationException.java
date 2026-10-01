package com.cofco.qiqihar.riskintelligence.experttraining;

import java.util.List;

public final class ExpertDatasetValidationException extends RuntimeException {
    private final List<Error> errors;

    ExpertDatasetValidationException(List<Error> errors) {
        super("Expert dataset validation failed");
        this.errors = List.copyOf(errors.subList(0, Math.min(errors.size(), 50)));
    }

    public List<Error> errors() { return errors; }

    public record Error(int row, String field, String code, String message) { }
}
