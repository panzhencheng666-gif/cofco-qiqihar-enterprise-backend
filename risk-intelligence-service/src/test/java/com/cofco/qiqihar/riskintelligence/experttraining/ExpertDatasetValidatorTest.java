package com.cofco.qiqihar.riskintelligence.experttraining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ExpertDatasetValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExpertDatasetValidator validator = new ExpertDatasetValidator(mapper);

    @Test
    void canonicalHashIgnoresObjectKeyAndCollectionOrderWhilePreservingDataset() throws Exception {
        var first = validator.validate(mapper.readTree(fixture()));
        var reordered = validator.validate(mapper.readTree(reorderedFixture()));

        assertThat(first.datasetSha256()).isEqualTo(reordered.datasetSha256());
        assertThat(first.counts()).containsEntry("train", 1).containsEntry("valid", 1)
                .containsEntry("test", 1);
        assertThat(first.dataset().path("examples")).anySatisfy(example ->
                assertThat(example.path("question").asText()).isEqualTo("Synthetic train question?"));
    }

    @Test
    void returnsBoundedStructuredErrorsForUnsafeAndLeakingDataset() throws Exception {
        var invalid = mapper.readTree(fixture());
        ((tools.jackson.databind.node.ObjectNode) invalid).put("unknown", true);
        assertThatThrownBy(() -> validator.validate(invalid))
                .isInstanceOf(ExpertDatasetValidationException.class)
                .satisfies(error -> {
                    var validation = (ExpertDatasetValidationException) error;
                    assertThat(validation.errors()).hasSizeBetween(1, 50);
                    assertThat(validation.errors()).extracting(ExpertDatasetValidationException.Error::code)
                            .contains("UNKNOWN_FIELD");
                });

        var unsafe = mapper.readTree(fixture());
        ((tools.jackson.databind.node.ObjectNode) unsafe.path("sources").get(0))
                .put("url", "https://user:secret@example.test/a");
        assertThatThrownBy(() -> validator.validate(unsafe))
                .isInstanceOf(ExpertDatasetValidationException.class)
                .satisfies(error -> assertThat(((ExpertDatasetValidationException) error).errors())
                        .extracting(ExpertDatasetValidationException.Error::code)
                        .contains("URL"));

        var leaking = mapper.readTree(fixture());
        ((tools.jackson.databind.node.ObjectNode) leaking.path("examples").get(1))
                .put("groupId", "group-train");
        assertThatThrownBy(() -> validator.validate(leaking))
                .isInstanceOf(ExpertDatasetValidationException.class)
                .satisfies(error -> assertThat(((ExpertDatasetValidationException) error).errors())
                        .extracting(ExpertDatasetValidationException.Error::code)
                        .contains("SPLIT_LEAKAGE"));
    }

    @Test
    void matchesPythonUnicodeBoundsCasefoldAndPortValidation() throws Exception {
        var unicode = mapper.readTree(fixture());
        ((tools.jackson.databind.node.ObjectNode) unicode.path("examples").get(0))
                .put("question", "😀".repeat(2000));
        assertThatCode(() -> validator.validate(unicode)).doesNotThrowAnyException();

        var casefoldLeak = mapper.readTree(fixture());
        ((tools.jackson.databind.node.ObjectNode) casefoldLeak.path("examples").get(0))
                .put("question", "Straße");
        ((tools.jackson.databind.node.ObjectNode) casefoldLeak.path("examples").get(1))
                .put("question", "STRASSE");
        assertThatThrownBy(() -> validator.validate(casefoldLeak))
                .isInstanceOf(ExpertDatasetValidationException.class)
                .satisfies(error -> assertThat(((ExpertDatasetValidationException) error).errors())
                        .extracting(ExpertDatasetValidationException.Error::code)
                        .contains("SPLIT_LEAKAGE"));

        var capitalSharpSLeak = mapper.readTree(fixture());
        ((tools.jackson.databind.node.ObjectNode) capitalSharpSLeak.path("examples").get(0))
                .put("question", "ẞ");
        ((tools.jackson.databind.node.ObjectNode) capitalSharpSLeak.path("examples").get(1))
                .put("question", "SS");
        assertThatThrownBy(() -> validator.validate(capitalSharpSLeak))
                .isInstanceOf(ExpertDatasetValidationException.class)
                .satisfies(error -> assertThat(((ExpertDatasetValidationException) error).errors())
                        .extracting(ExpertDatasetValidationException.Error::code)
                        .contains("SPLIT_LEAKAGE"));

        var invalidPort = mapper.readTree(fixture());
        ((tools.jackson.databind.node.ObjectNode) invalidPort.path("sources").get(0))
                .put("url", "https://example.test:70000/train");
        assertThatThrownBy(() -> validator.validate(invalidPort))
                .isInstanceOf(ExpertDatasetValidationException.class);
    }

    static String fixture() {
        return """
                {"schemaVersion":1,"datasetId":"synthetic-dataset","sources":[
                  {"sourceId":"source-train","title":"Synthetic train source","url":"https://example.test/train","license":"OWNED","licenseEvidenceUrl":"https://example.test/license/train","contentSha256":"%s","usage":"TRAINING_ALLOWED"},
                  {"sourceId":"source-valid","title":"Synthetic valid source","url":"https://example.test/valid","license":"CC-BY-4.0","licenseEvidenceUrl":"https://example.test/license/valid","contentSha256":"%s","usage":"TRAINING_ALLOWED"},
                  {"sourceId":"source-test","title":"Synthetic test source","url":"https://example.test/test","license":"PUBLIC_DOMAIN","licenseEvidenceUrl":"https://example.test/license/test","contentSha256":"%s","usage":"TRAINING_ALLOWED"}],
                 "examples":[
                  {"exampleId":"example-train","sourceIds":["source-train"],"groupId":"group-train","split":"train","question":"Synthetic train question?","context":"Synthetic train context.","answer":"Synthetic train answer.","origin":"SYNTHETIC","verification":{"status":"VERIFIED","reference":"synthetic reference"}},
                  {"exampleId":"example-valid","sourceIds":["source-valid"],"groupId":"group-valid","split":"valid","question":"Synthetic valid question?","context":"Synthetic valid context.","answer":"Synthetic valid answer.","origin":"SYNTHETIC","verification":{"status":"VERIFIED","reference":"synthetic reference"}},
                  {"exampleId":"example-test","sourceIds":["source-test"],"groupId":"group-test","split":"test","question":"Synthetic test question?","context":"Synthetic test context.","answer":"Synthetic test answer.","origin":"SYNTHETIC","verification":{"status":"VERIFIED","reference":"synthetic reference"}}]}
                """.formatted("a".repeat(64), "b".repeat(64), "c".repeat(64));
    }

    private static String reorderedFixture() {
        return """
                {"examples":[
                  {"verification":{"reference":"synthetic reference","status":"VERIFIED"},"origin":"SYNTHETIC","answer":"Synthetic test answer.","context":"Synthetic test context.","question":"Synthetic test question?","split":"test","groupId":"group-test","sourceIds":["source-test"],"exampleId":"example-test"},
                  {"verification":{"reference":"synthetic reference","status":"VERIFIED"},"origin":"SYNTHETIC","answer":"Synthetic train answer.","context":"Synthetic train context.","question":"Synthetic train question?","split":"train","groupId":"group-train","sourceIds":["source-train"],"exampleId":"example-train"},
                  {"verification":{"reference":"synthetic reference","status":"VERIFIED"},"origin":"SYNTHETIC","answer":"Synthetic valid answer.","context":"Synthetic valid context.","question":"Synthetic valid question?","split":"valid","groupId":"group-valid","sourceIds":["source-valid"],"exampleId":"example-valid"}],
                 "sources":[
                  {"usage":"TRAINING_ALLOWED","contentSha256":"%s","licenseEvidenceUrl":"https://example.test/license/test","license":"PUBLIC_DOMAIN","url":"https://example.test/test","title":"Synthetic test source","sourceId":"source-test"},
                  {"usage":"TRAINING_ALLOWED","contentSha256":"%s","licenseEvidenceUrl":"https://example.test/license/train","license":"OWNED","url":"https://example.test/train","title":"Synthetic train source","sourceId":"source-train"},
                  {"usage":"TRAINING_ALLOWED","contentSha256":"%s","licenseEvidenceUrl":"https://example.test/license/valid","license":"CC-BY-4.0","url":"https://example.test/valid","title":"Synthetic valid source","sourceId":"source-valid"}],
                 "datasetId":"synthetic-dataset","schemaVersion":1}
                """.formatted("c".repeat(64), "a".repeat(64), "b".repeat(64));
    }
}
