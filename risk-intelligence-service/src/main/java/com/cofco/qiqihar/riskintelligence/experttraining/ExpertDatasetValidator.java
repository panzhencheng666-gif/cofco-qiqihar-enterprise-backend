package com.cofco.qiqihar.riskintelligence.experttraining;

import com.ibm.icu.lang.UCharacter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public final class ExpertDatasetValidator {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> TOP = Set.of("schemaVersion", "datasetId", "sources", "examples");
    private static final Set<String> SOURCE = Set.of("sourceId", "title", "url", "license",
            "licenseEvidenceUrl", "contentSha256", "usage");
    private static final Set<String> EXAMPLE = Set.of("exampleId", "sourceIds", "groupId", "split",
            "question", "context", "answer", "origin", "verification");
    private static final Set<String> VERIFICATION = Set.of("status", "reference");
    private static final Set<String> SPLITS = Set.of("train", "valid", "test");
    private final ObjectMapper json;

    public ExpertDatasetValidator(ObjectMapper json) { this.json = json; }

    public ValidatedDataset validate(JsonNode input) {
        Errors errors = new Errors();
        if (!shape(input, TOP, 0, "", errors)) throw errors.exception();
        if (!input.path("schemaVersion").isInt() || input.path("schemaVersion").intValue() != 1) {
            errors.add(0, "schemaVersion", "VERSION", "Expected integer schema version 1.");
        }
        identifier(input.path("datasetId"), 0, "datasetId", errors);
        Map<String, Source> sourceById = new HashMap<>();
        List<JsonNode> sources = new ArrayList<>();
        JsonNode sourceValues = input.path("sources");
        if (array(sourceValues, 3, 1000, 0, "sources", errors)) {
            for (int index = 0; index < sourceValues.size(); index++) {
                int row = index + 1;
                JsonNode source = sourceValues.get(index);
                String field = "sources[" + row + "]";
                if (!shape(source, SOURCE, row, field, errors)) continue;
                boolean valid = identifier(source.path("sourceId"), row, field + ".sourceId", errors)
                        & text(source.path("title"), 300, row, field + ".title", false, errors)
                        & url(source.path("url"), row, field + ".url", errors)
                        & url(source.path("licenseEvidenceUrl"), row, field + ".licenseEvidenceUrl", errors)
                        & choice(source.path("license"), Set.of("CC0-1.0", "CC-BY-4.0", "PUBLIC_DOMAIN", "OWNED"), row, field + ".license", errors)
                        & choice(source.path("usage"), Set.of("TRAINING_ALLOWED"), row, field + ".usage", errors);
                String digest = source.path("contentSha256").asText("");
                if (!SHA.matcher(digest).matches()) {
                    errors.add(row, field + ".contentSha256", "SHA256", "Expected 64 lowercase hexadecimal characters.");
                    valid = false;
                }
                if (!valid) continue;
                String id = source.path("sourceId").asText();
                if (sourceById.containsKey(id)) {
                    errors.add(row, field + ".sourceId", "DUPLICATE", "Source identifier is duplicated.");
                } else {
                    JsonNode copy = source.deepCopy();
                    sourceById.put(id, new Source(copy, row));
                    sources.add(copy);
                }
            }
        }

        Set<String> usedSources = new HashSet<>();
        Set<String> exampleIds = new HashSet<>();
        Set<String> pairs = new HashSet<>();
        Map<String, Map<String, String>> protectedValues = new HashMap<>();
        for (String key : List.of("sourceIds", "contentSha256", "groupId", "question")) {
            protectedValues.put(key, new HashMap<>());
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String split : List.of("train", "valid", "test")) counts.put(split, 0);
        List<JsonNode> examples = new ArrayList<>();
        JsonNode exampleValues = input.path("examples");
        if (array(exampleValues, 3, 10000, 0, "examples", errors)) {
            for (int index = 0; index < exampleValues.size(); index++) {
                int row = index + 1;
                JsonNode example = exampleValues.get(index);
                String field = "examples[" + row + "]";
                if (!shape(example, EXAMPLE, row, field, errors)) continue;
                boolean valid = identifier(example.path("exampleId"), row, field + ".exampleId", errors)
                        & identifier(example.path("groupId"), row, field + ".groupId", errors)
                        & choice(example.path("split"), SPLITS, row, field + ".split", errors)
                        & choice(example.path("origin"), Set.of("HUMAN_AUTHORED", "SYNTHETIC"), row, field + ".origin", errors)
                        & text(example.path("question"), 2000, row, field + ".question", true, errors)
                        & text(example.path("context"), 12000, row, field + ".context", true, errors)
                        & text(example.path("answer"), 6000, row, field + ".answer", true, errors);
                JsonNode verification = example.path("verification");
                boolean verificationValid = shape(verification, VERIFICATION, row, field + ".verification", errors);
                valid &= verificationValid;
                if (verificationValid) {
                    valid &= choice(verification.path("status"), Set.of("VERIFIED"), row,
                            field + ".verification.status", errors);
                    valid &= text(verification.path("reference"), 300, row,
                            field + ".verification.reference", false, errors);
                }
                JsonNode references = example.path("sourceIds");
                boolean referencesValid = array(references, 1, 10, row, field + ".sourceIds", errors);
                valid &= referencesValid;
                Set<String> seen = new HashSet<>();
                if (referencesValid) for (JsonNode referenceNode : references) {
                    boolean referenceValid = identifier(referenceNode, row, field + ".sourceIds", errors);
                    String reference = referenceNode.asText("");
                    if (!seen.add(reference)) {
                        errors.add(row, field + ".sourceIds", "DUPLICATE", "Source references must be unique.");
                        referenceValid = false;
                    }
                    if (!sourceById.containsKey(reference)) {
                        errors.add(row, field + ".sourceIds", "REFERENCE", "Source reference is not valid.");
                        referenceValid = false;
                    }
                    valid &= referenceValid;
                }
                if (!valid) continue;
                String identifier = example.path("exampleId").asText();
                if (!exampleIds.add(identifier)) {
                    errors.add(row, field + ".exampleId", "DUPLICATE", "Example identifier is duplicated.");
                }
                String split = example.path("split").asText();
                String question = normalized(example.path("question").asText());
                String pair = question + "\u0000" + normalized(example.path("answer").asText());
                if (!pairs.add(pair)) {
                    errors.add(row, field + ".answer", "DUPLICATE", "Normalized question and answer pair is duplicated.");
                }
                splitCheck(protectedValues, "question", question, split, row, errors);
                splitCheck(protectedValues, "groupId", example.path("groupId").asText(), split, row, errors);
                List<String> sortedReferences = new ArrayList<>();
                for (JsonNode referenceNode : references) {
                    String reference = referenceNode.asText();
                    sortedReferences.add(reference);
                    usedSources.add(reference);
                    splitCheck(protectedValues, "sourceIds", reference, split, row, errors);
                    splitCheck(protectedValues, "contentSha256",
                            sourceById.get(reference).value().path("contentSha256").asText(), split, row, errors);
                }
                sortedReferences.sort(String::compareTo);
                ObjectNode copy = (ObjectNode) example.deepCopy();
                copy.set("sourceIds", json.valueToTree(sortedReferences));
                examples.add(copy);
                counts.put(split, counts.get(split) + 1);
            }
        }
        for (Map.Entry<String, Source> entry : sourceById.entrySet()) {
            if (!usedSources.contains(entry.getKey())) {
                errors.add(entry.getValue().row(), "sources[" + entry.getValue().row() + "].sourceId",
                        "UNUSED_SOURCE", "Source is not used by any valid example.");
            }
        }
        if (counts.values().stream().anyMatch(count -> count == 0)) {
            errors.add(0, "examples", "EMPTY_SPLIT", "Train, valid and test splits must each contain an example.");
        }
        if (!errors.values.isEmpty()) throw errors.exception();
        sources.sort(Comparator.comparing(node -> node.path("sourceId").asText()));
        examples.sort(Comparator.comparing(node -> node.path("exampleId").asText()));
        ObjectNode dataset = json.createObjectNode();
        dataset.put("schemaVersion", 1);
        dataset.put("datasetId", input.path("datasetId").asText());
        dataset.set("sources", json.valueToTree(sources));
        dataset.set("examples", json.valueToTree(examples));
        return new ValidatedDataset(dataset, sha256(canonicalBytes(dataset)), Map.copyOf(counts));
    }

    public byte[] canonicalBytes(JsonNode value) {
        return json.writeValueAsBytes(sorted(value));
    }

    public String canonicalSha256(JsonNode value) { return sha256(canonicalBytes(value)); }

    private JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = json.createObjectNode();
            TreeMap<String, JsonNode> properties = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> iterator = value.properties().iterator();
            iterator.forEachRemaining(entry -> properties.put(entry.getKey(), entry.getValue()));
            properties.forEach((key, child) -> result.set(key, sorted(child)));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = json.createArrayNode();
            value.forEach(child -> result.add(sorted(child)));
            return result;
        }
        return value.deepCopy();
    }

    private static void splitCheck(Map<String, Map<String, String>> values, String kind,
            String value, String split, int row, Errors errors) {
        String previous = values.get(kind).putIfAbsent(value, split);
        if (previous != null && !previous.equals(split)) {
            errors.add(row, "examples[" + row + "]." + kind, "SPLIT_LEAKAGE",
                    "A protected value is shared across dataset splits.");
        }
    }

    private static boolean shape(JsonNode value, Set<String> keys, int row, String field, Errors errors) {
        if (!value.isObject()) {
            errors.add(row, field.isEmpty() ? "$" : field, "TYPE", "Expected an object.");
            return false;
        }
        boolean valid = true;
        for (String key : keys) if (!value.has(key)) {
            errors.add(row, field.isEmpty() ? key : field + "." + key, "REQUIRED", "Required field is missing.");
            valid = false;
        }
        if (value.properties().stream().anyMatch(entry -> !keys.contains(entry.getKey()))) {
            errors.add(row, field.isEmpty() ? "$" : field, "UNKNOWN_FIELD", "Unknown fields are not allowed.");
            valid = false;
        }
        return valid;
    }

    private static boolean identifier(JsonNode value, int row, String field, Errors errors) {
        if (!value.isTextual() || !ID.matcher(value.asText()).matches()) {
            errors.add(row, field, "IDENTIFIER", "Expected a safe ASCII identifier of 1 to 64 characters.");
            return false;
        }
        return true;
    }

    private static boolean text(JsonNode value, int limit, int row, String field,
            boolean multiline, Errors errors) {
        if (!value.isTextual()) {
            errors.add(row, field, "TYPE", "Expected a string.");
            return false;
        }
        String text = value.asText();
        if (text.isBlank() || text.codePointCount(0, text.length()) > limit) {
            errors.add(row, field, "LENGTH", "Text is empty or exceeds the field limit.");
            return false;
        }
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            int type = Character.getType(codePoint);
            boolean allowedWhitespace = multiline && (codePoint == '\t' || codePoint == '\n' || codePoint == '\r');
            if (!allowedWhitespace && (type == Character.CONTROL || type == Character.SURROGATE || type == Character.FORMAT)) {
                errors.add(row, field, "CHARACTERS", "Invalid Unicode or control characters.");
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    private static boolean choice(JsonNode value, Set<String> choices, int row, String field, Errors errors) {
        if (!value.isTextual() || !choices.contains(value.asText())) {
            errors.add(row, field, "VALUE", "Unsupported field value.");
            return false;
        }
        return true;
    }

    private static boolean url(JsonNode value, int row, String field, Errors errors) {
        if (!text(value, 2048, row, field, false, errors)) return false;
        try {
            URI uri = URI.create(value.asText());
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getPort() > 65535
                    || value.asText().contains("\\") || value.asText().chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException();
            }
            return true;
        } catch (IllegalArgumentException exception) {
            errors.add(row, field, "URL", "Expected an HTTPS URL with host and no credentials.");
            return false;
        }
    }

    private static boolean array(JsonNode value, int lower, int upper, int row, String field, Errors errors) {
        if (!value.isArray()) {
            errors.add(row, field, "TYPE", "Expected an array.");
            return false;
        }
        if (value.size() < lower || value.size() > upper) {
            errors.add(row, field, "LENGTH", "Array size is outside the allowed range.");
            return false;
        }
        return true;
    }

    private static String normalized(String value) {
        return UCharacter.foldCase(Normalizer.normalize(value, Normalizer.Form.NFKC), true)
                .trim().replaceAll("(?U)\\s+", " ");
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record ValidatedDataset(JsonNode dataset, String datasetSha256,
            Map<String, Integer> counts) { }
    private record Source(JsonNode value, int row) { }

    private static final class Errors {
        private final List<ExpertDatasetValidationException.Error> values = new ArrayList<>();
        void add(int row, String field, String code, String message) {
            if (values.size() < 50) values.add(new ExpertDatasetValidationException.Error(row, field, code, message));
        }
        ExpertDatasetValidationException exception() { return new ExpertDatasetValidationException(values); }
    }
}
