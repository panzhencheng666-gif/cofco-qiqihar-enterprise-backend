package com.cofco.qiqihar.riskintelligence.experttraining;

import com.cofco.qiqihar.riskintelligence.security.RiskApiException;
import com.cofco.qiqihar.riskintelligence.security.RiskBusinessSession;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ExpertTrainingService {
    private static final String MODEL_REFERENCE = "qiliang-risk-llm-v1";
    private static final Pattern IDEMPOTENCY = Pattern.compile("[\\x21-\\x7e]{1,120}");
    private static final Set<String> TASK_FIELDS = Set.of("datasetSnapshotId", "idempotencyKey", "config");
    private static final Set<String> CONFIG_FIELDS = Set.of("iterations", "maxSeqLength", "numLayers",
            "seed", "learningRate");
    private final ExpertTrainingRepository repository;
    private final ExpertDatasetValidator validator;
    private final ObjectMapper json;
    private final Clock clock;

    public ExpertTrainingService(ExpertTrainingRepository repository, ExpertDatasetValidator validator,
            ObjectMapper json, Clock clock) {
        this.repository = repository;
        this.validator = validator;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public ExpertTrainingRepository.DatasetSnapshot createDataset(JsonNode body,
            RiskBusinessSession session) {
        requireRoot(session);
        return repository.saveDataset(validator.validate(body), session.subjectId(), clock.instant());
    }

    @Transactional
    public ExpertTrainingRepository.TaskView createTask(JsonNode body, RiskBusinessSession session) {
        requireRoot(session);
        var errors = new java.util.ArrayList<ExpertDatasetValidationException.Error>();
        exactObject(body, TASK_FIELDS, "$", errors);
        UUID snapshotId = uuid(body.path("datasetSnapshotId"), "datasetSnapshotId", errors);
        String key = body.path("idempotencyKey").asText("");
        if (!IDEMPOTENCY.matcher(key).matches()) {
            errors.add(new ExpertDatasetValidationException.Error(0, "idempotencyKey", "IDENTIFIER",
                    "Expected safe ASCII text of 1 to 120 characters."));
        }
        JsonNode config = body.path("config");
        exactObject(config, CONFIG_FIELDS, "config", errors);
        integer(config, "iterations", 1, 1000, errors);
        integer(config, "maxSeqLength", 256, 2048, errors);
        integer(config, "numLayers", 1, 8, errors);
        integer(config, "seed", 0, Integer.MAX_VALUE, errors);
        JsonNode learningRate = config.path("learningRate");
        double rate = learningRate.asDouble(Double.NaN);
        if (!learningRate.isNumber() || !Double.isFinite(rate) || rate < 0.000001d || rate > 0.0001d) {
            errors.add(new ExpertDatasetValidationException.Error(0, "config.learningRate", "RANGE",
                    "Learning rate must be finite and between 0.000001 and 0.0001."));
        }
        if (!errors.isEmpty()) throw new ExpertDatasetValidationException(errors);
        String requestHash = validator.canonicalSha256(body);
        return repository.requestTask(snapshotId, session.subjectId(), key, config.deepCopy(),
                requestHash, MODEL_REFERENCE, clock.instant());
    }

    @Transactional(readOnly = true)
    public ExpertTrainingRepository.Overview overview(RiskBusinessSession session) {
        requireRoot(session);
        return repository.overview();
    }

    @Transactional
    public ExpertTrainingRepository.TaskView cancel(UUID taskId, RiskBusinessSession session) {
        requireRoot(session);
        return repository.cancel(taskId, session.subjectId(), clock.instant());
    }

    private static void requireRoot(RiskBusinessSession session) {
        if (session == null || !session.rootAdministrator()) {
            throw new RiskApiException(HttpStatus.FORBIDDEN, "RISK_GLOBAL_MODEL_FORBIDDEN",
                    "全域模型仅允许根管理员访问");
        }
    }

    private static void exactObject(JsonNode value, Set<String> fields, String path,
            java.util.List<ExpertDatasetValidationException.Error> errors) {
        if (!value.isObject()) {
            errors.add(new ExpertDatasetValidationException.Error(0, path, "TYPE", "Expected an object."));
            return;
        }
        Set<String> present = new HashSet<>();
        value.properties().forEach(entry -> present.add(entry.getKey()));
        for (String field : fields) if (!present.contains(field)) {
            errors.add(new ExpertDatasetValidationException.Error(0,
                    "$".equals(path) ? field : path + "." + field, "REQUIRED", "Required field is missing."));
        }
        if (!fields.containsAll(present)) {
            errors.add(new ExpertDatasetValidationException.Error(0, path, "UNKNOWN_FIELD",
                    "Unknown fields are not allowed."));
        }
    }

    private static UUID uuid(JsonNode value, String path,
            java.util.List<ExpertDatasetValidationException.Error> errors) {
        try { return UUID.fromString(value.asText()); }
        catch (RuntimeException exception) {
            errors.add(new ExpertDatasetValidationException.Error(0, path, "UUID", "Expected a UUID."));
            return new UUID(0, 0);
        }
    }

    private static void integer(JsonNode config, String field, int lower, int upper,
            java.util.List<ExpertDatasetValidationException.Error> errors) {
        JsonNode value = config.path(field);
        if (!value.isIntegralNumber() || value.longValue() < lower || value.longValue() > upper) {
            errors.add(new ExpertDatasetValidationException.Error(0, "config." + field, "RANGE",
                    "Integer is outside the allowed range."));
        }
    }
}
