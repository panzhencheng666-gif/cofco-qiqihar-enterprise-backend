package com.cofco.qiqihar.graintrade.shared.interfaceadapter;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.util.MultiValueMap;

public final class StrictQueryParameters {

    private final Map<String, String> values;
    private final Supplier<ClientRequestException> invalidRequest;

    private StrictQueryParameters(
            Map<String, String> values,
            Supplier<ClientRequestException> invalidRequest) {
        this.values = Map.copyOf(values);
        this.invalidRequest = invalidRequest;
    }

    public static StrictQueryParameters parse(
            MultiValueMap<String, String> parameters,
            Predicate<String> allowedName,
            Supplier<ClientRequestException> invalidRequest) {
        Map<String, String> values = new LinkedHashMap<>();
        parameters.forEach((name, candidates) -> {
            if (!allowedName.test(name) || candidates == null || candidates.size() != 1) {
                throw invalidRequest.get();
            }
            String value = candidates.getFirst();
            if (value == null || value.isBlank()) {
                throw invalidRequest.get();
            }
            values.put(name, value);
        });
        return new StrictQueryParameters(values, invalidRequest);
    }

    public String required(String name) {
        String value = values.get(name);
        if (value == null) {
            throw invalidRequest.get();
        }
        return value;
    }

    public String optional(String name) {
        return values.get(name);
    }

    public int integer(String name, int defaultValue) {
        String value = values.get(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw invalidRequest.get();
        }
    }

    public Map<String, String> values() {
        return values;
    }
}
