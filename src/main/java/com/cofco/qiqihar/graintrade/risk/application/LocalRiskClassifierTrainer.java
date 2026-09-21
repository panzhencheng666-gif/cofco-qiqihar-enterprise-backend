package com.cofco.qiqihar.graintrade.risk.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class LocalRiskClassifierTrainer implements RiskTrainerBackend {
    private static final Pattern WORD=Pattern.compile("[\\p{IsHan}]{1,4}|[\\p{L}\\p{N}_-]{2,}");
    private final ObjectMapper json;
    private final Path artifactRoot;

    @Autowired
    public LocalRiskClassifierTrainer(ObjectMapper json,
            @Value("${qiqihar.risk.training.artifact-root:}") String artifactRoot) {
        this(json,artifactRoot==null || artifactRoot.isBlank()?null:Path.of(artifactRoot));
    }

    public LocalRiskClassifierTrainer(ObjectMapper json,Path artifactRoot) {
        this.json=json;
        this.artifactRoot=artifactRoot;
    }

    @Override
    public boolean supports(String modelKind) {
        return "RISK_CLASSIFIER".equals(modelKind);
    }

    @Override
    public RiskTrainingArtifact train(RiskTrainingJob job) throws Exception {
        if (artifactRoot==null) {
            throw new IllegalStateException(
                    "未配置 QIQIHAR_RISK_MODEL_ARTIFACT_ROOT，模型工件不会写入临时目录");
        }
        if (job.examples().size()<3) throw new IllegalArgumentException("训练至少需要 3 条监督标签");
        List<RiskTrainingExample> ordered=job.examples().stream()
                .sorted(java.util.Comparator.comparing(RiskTrainingExample::resolvedAt)
                        .thenComparing(RiskTrainingExample::assessmentId))
                .toList();
        long positives=ordered.stream().filter(RiskTrainingExample::positive).count();
        if (positives==0 || positives==ordered.size()) {
            throw new IllegalArgumentException("训练同时需要正例和负例");
        }
        int validationSize=Math.max(1,ordered.size()/5);
        int split=ordered.size()-validationSize;
        List<RiskTrainingExample> training=new ArrayList<>(ordered.subList(0,split));
        List<RiskTrainingExample> validation=new ArrayList<>(ordered.subList(split,ordered.size()));
        ensureBothClassesInTraining(training,validation);

        Model model=fit(training);
        Metrics metrics=evaluate(model,validation);
        Map<String,Object> metricDefinition=new LinkedHashMap<>();
        metricDefinition.put("exampleCount",ordered.size());
        metricDefinition.put("trainingCount",training.size());
        metricDefinition.put("validationCount",validation.size());
        metricDefinition.put("positiveLabelCount",positives);
        metricDefinition.put("negativeLabelCount",ordered.size()-positives);
        metricDefinition.put("accuracy",metrics.accuracy());
        metricDefinition.put("precision",metrics.precision());
        metricDefinition.put("recall",metrics.recall());
        metricDefinition.put("f1",metrics.f1());
        metricDefinition.put("validationStrategy","time_ordered_holdout");
        Map<String,Object> thresholds=Map.of("positiveProbability",0.5d,
                "promotionRequiresShadowEvaluation",true);

        ArtifactDocument document=new ArtifactDocument(
                "bernoulli-naive-bayes","1",job.modelId(),job.modelCode(),job.domainCode(),
                job.modelVersion(),job.trainingSnapshotId(),job.randomSeed(),
                model.positiveDocuments(),model.negativeDocuments(),model.vocabulary(),
                model.positiveTokenCounts(),model.negativeTokenCounts(),metricDefinition,thresholds);
        byte[] bytes=json.writeValueAsBytes(document);
        String hash=sha256(bytes);
        Path modelDirectory=artifactRoot.resolve(safeSegment(job.modelCode()));
        Files.createDirectories(modelDirectory);
        Path target=modelDirectory.resolve("v%d-%s.json".formatted(job.modelVersion(),hash));
        writeAtomically(target,bytes);
        return new RiskTrainingArtifact(target.toAbsolutePath().normalize().toString(),hash,
                metricDefinition,thresholds,"builtin-bernoulli-naive-bayes","1","RETRAIN");
    }

    private static void ensureBothClassesInTraining(
            List<RiskTrainingExample> training,List<RiskTrainingExample> validation) {
        if (containsBoth(training)) return;
        boolean missingPositive=training.stream().noneMatch(RiskTrainingExample::positive);
        for (int index=0;index<validation.size();index++) {
            if (validation.get(index).positive()==missingPositive) {
                RiskTrainingExample missingClass=validation.remove(index);
                RiskTrainingExample holdout=training.remove(training.size()-1);
                training.add(missingClass);
                validation.add(holdout);
                break;
            }
        }
        if (!containsBoth(training)) throw new IllegalArgumentException("训练集无法同时保留正例和负例");
    }

    private static boolean containsBoth(List<RiskTrainingExample> examples) {
        return examples.stream().anyMatch(RiskTrainingExample::positive)
                && examples.stream().anyMatch(example -> !example.positive());
    }

    private static Model fit(List<RiskTrainingExample> examples) {
        Map<String,Integer> positive=new TreeMap<>();
        Map<String,Integer> negative=new TreeMap<>();
        Set<String> vocabulary=new java.util.TreeSet<>();
        int positiveDocuments=0;
        for (RiskTrainingExample example:examples) {
            if (example.positive()) positiveDocuments++;
            Set<String> tokens=tokens(example.canonicalText());
            vocabulary.addAll(tokens);
            Map<String,Integer> target=example.positive()?positive:negative;
            tokens.forEach(token -> target.merge(token,1,Integer::sum));
        }
        return new Model(positiveDocuments,examples.size()-positiveDocuments,
                List.copyOf(vocabulary),positive,negative);
    }

    private static double probability(Model model,String text) {
        Set<String> present=tokens(text);
        int total=model.positiveDocuments()+model.negativeDocuments();
        double positiveLog=Math.log((model.positiveDocuments()+1d)/(total+2d));
        double negativeLog=Math.log((model.negativeDocuments()+1d)/(total+2d));
        for (String token:model.vocabulary()) {
            double pPositive=(model.positiveTokenCounts().getOrDefault(token,0)+1d)
                    /(model.positiveDocuments()+2d);
            double pNegative=(model.negativeTokenCounts().getOrDefault(token,0)+1d)
                    /(model.negativeDocuments()+2d);
            positiveLog+=Math.log(present.contains(token)?pPositive:1d-pPositive);
            negativeLog+=Math.log(present.contains(token)?pNegative:1d-pNegative);
        }
        double maximum=Math.max(positiveLog,negativeLog);
        double positiveExp=Math.exp(positiveLog-maximum);
        double negativeExp=Math.exp(negativeLog-maximum);
        return positiveExp/(positiveExp+negativeExp);
    }

    private static Metrics evaluate(Model model,List<RiskTrainingExample> validation) {
        long tp=0,tn=0,fp=0,fn=0;
        for (RiskTrainingExample example:validation) {
            boolean predicted=probability(model,example.canonicalText())>=0.5d;
            if (predicted && example.positive()) tp++;
            else if (predicted) fp++;
            else if (example.positive()) fn++;
            else tn++;
        }
        double accuracy=ratio(tp+tn,validation.size());
        double precision=ratio(tp,tp+fp);
        double recall=ratio(tp,tp+fn);
        double f1=precision+recall==0?0:2d*precision*recall/(precision+recall);
        return new Metrics(round(accuracy),round(precision),round(recall),round(f1));
    }

    private static Set<String> tokens(String text) {
        if (text==null || text.isBlank()) return Set.of();
        var result=new HashSet<String>();
        var matcher=WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    private static String safeSegment(String value) {
        String safe=value.replaceAll("[^a-zA-Z0-9._-]","_");
        if (safe.isBlank() || safe.equals(".") || safe.equals("..")) {
            throw new IllegalArgumentException("模型编码不能映射为空工件目录");
        }
        return safe;
    }

    private static void writeAtomically(Path target,byte[] bytes) throws IOException {
        if (Files.exists(target)) {
            if (!MessageDigest.isEqual(Files.readAllBytes(target),bytes)) {
                throw new IllegalStateException("同名模型工件内容不一致，拒绝覆盖: "+target);
            }
            return;
        }
        Path temporary=Files.createTempFile(target.getParent(),target.getFileName().toString(),".tmp");
        try {
            Files.write(temporary,bytes);
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static double ratio(long numerator,long denominator) {
        return denominator==0?0d:(double)numerator/denominator;
    }

    private static double round(double value) {
        return Math.round(value*1_000_000d)/1_000_000d;
    }

    private record Model(int positiveDocuments,int negativeDocuments,List<String> vocabulary,
            Map<String,Integer> positiveTokenCounts,Map<String,Integer> negativeTokenCounts) { }
    private record Metrics(double accuracy,double precision,double recall,double f1) { }
    private record ArtifactDocument(String algorithm,String algorithmVersion,UUID modelId,
            String modelCode,String domainCode,int modelVersion,UUID trainingSnapshotId,long randomSeed,
            int positiveDocuments,int negativeDocuments,List<String> vocabulary,
            Map<String,Integer> positiveTokenCounts,Map<String,Integer> negativeTokenCounts,
            Map<String,Object> metrics,Map<String,Object> thresholds) { }
}
