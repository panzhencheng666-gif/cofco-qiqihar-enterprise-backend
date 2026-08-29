package com.cofco.qiqihar.graintrade.samplepoint.identity.application;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Conservative three-way decision for a visible business sample identity. */
public record SampleIdentityAssessment(
        Outcome outcome,
        String reasonCode,
        String reasonMessage,
        UUID matchedSamplePointId,
        List<Candidate> candidates) {

    public enum Outcome { MATCHED, DISTINCT, REVIEW_REQUIRED }

    public SampleIdentityAssessment {
        if (outcome == null || blank(reasonCode) || blank(reasonMessage)) {
            throw new IllegalArgumentException("INVALID_SAMPLE_IDENTITY_ASSESSMENT");
        }
        candidates = List.copyOf(candidates == null ? List.of() : candidates);
        if ((outcome == Outcome.MATCHED) != (matchedSamplePointId != null)) {
            throw new IllegalArgumentException("INVALID_SAMPLE_IDENTITY_MATCH");
        }
    }

    public static SampleIdentityAssessment assess(SubjectInput input, List<Candidate> sourceCandidates) {
        if (input == null) throw new IllegalArgumentException("SAMPLE_IDENTITY_INPUT_REQUIRED");
        String nameKey = normalizedName(input.sampleName());
        String contactKey = normalizedContact(input.sampleContact());
        Map<UUID, Candidate> sameNameByPoint = new LinkedHashMap<>();
        for (Candidate candidate : sourceCandidates == null ? List.<Candidate>of() : sourceCandidates) {
            if (candidate != null && normalizedName(candidate.canonicalName()).equals(nameKey)) {
                sameNameByPoint.putIfAbsent(candidate.samplePointId(), candidate);
            }
        }
        List<Candidate> sameName = List.copyOf(sameNameByPoint.values());
        if (sameName.isEmpty()) {
            List<Candidate> sameCoordinate = coordinateMatches(input, sourceCandidates);
            if (!sameCoordinate.isEmpty()) {
                return review("SAMPLE_COORDINATE_SHARED_REVIEW_REQUIRED",
                        "该经纬度已由其他样本点使用；如确属不同对象共用地址，需核验证据后继续",
                        sameCoordinate);
            }
            return distinct("SAMPLE_IDENTITY_NO_CANDIDATE", "未发现需要复用的历史样本身份", sameName);
        }
        if (contactKey.isEmpty()) {
            return review("SAMPLE_IDENTITY_EVIDENCE_INCOMPLETE",
                    "存在同名历史样本点，但联系方式不足，需核验后继续", sameName);
        }
        List<Candidate> exact = sameName.stream()
                .filter(candidate -> normalizedContact(candidate.sampleContact()).equals(contactKey))
                .toList();
        if (exact.size() > 1) {
            return review("SAMPLE_IDENTITY_MULTIPLE_MATCHES",
                    "同一姓名和联系方式已关联多个样本点，需选择规范身份", sameName);
        }
        if (exact.size() == 1) {
            Candidate candidate = exact.getFirst();
            return new SampleIdentityAssessment(Outcome.MATCHED, "SAMPLE_IDENTITY_MATCHED",
                    "已按稳定姓名和联系方式匹配历史样本身份；本期地区和经纬度作为本期业务事实保存",
                    candidate.samplePointId(), sameName);
        }
        boolean everyContactDistinguishes = sameName.stream()
                .map(Candidate::sampleContact)
                .noneMatch(SampleIdentityAssessment::blank);
        boolean everyLocationDistinguishes = sameName.stream()
                .noneMatch(candidate -> sameLocation(input, candidate));
        if (everyContactDistinguishes && everyLocationDistinguishes) {
            return distinct("SAMPLE_IDENTITY_CLEARLY_DISTINCT",
                    "同名对象的联系方式和位置均不同，可作为不同样本点", sameName);
        }
        return review("SAMPLE_IDENTITY_CONTACT_CONFLICT",
                "同名对象的联系方式发生变化，但位置证据不足以确认是不同对象", sameName);
    }

    private static List<Candidate> coordinateMatches(
            SubjectInput input, List<Candidate> candidates) {
        Map<UUID, Candidate> matches = new LinkedHashMap<>();
        for (Candidate candidate : candidates == null ? List.<Candidate>of() : candidates) {
            if (candidate != null && sameLocation(input, candidate)) {
                matches.putIfAbsent(candidate.samplePointId(), candidate);
            }
        }
        return List.copyOf(matches.values());
    }

    private static SampleIdentityAssessment distinct(
            String code, String message, List<Candidate> candidates) {
        return new SampleIdentityAssessment(Outcome.DISTINCT, code, message, null, candidates);
    }

    private static SampleIdentityAssessment review(
            String code, String message, List<Candidate> candidates) {
        return new SampleIdentityAssessment(Outcome.REVIEW_REQUIRED, code, message, null, candidates);
    }

    private static boolean sameLocation(SubjectInput input, Candidate candidate) {
        return input.regionCode().equals(candidate.regionCode())
                && input.longitude().compareTo(candidate.longitude()) == 0
                && input.latitude().compareTo(candidate.latitude()) == 0;
    }

    public static String normalizedName(String value) {
        return normalize(value).replaceAll("[\\s\\u3000]+", "");
    }

    public static String normalizedContact(String value) {
        return normalize(value).replaceAll("[\\s\\u3000()（）-]+", "");
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String folded = Normalizer.normalize(value, Normalizer.Form.NFKC).strip()
                .toLowerCase(Locale.ROOT);
        return Normalizer.normalize(folded, Normalizer.Form.NFKD)
                .replaceAll("[\\u0300-\\u036f]+", "");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public record SubjectInput(
            String domainCode,
            String sampleName,
            String sampleContact,
            String regionCode,
            BigDecimal longitude,
            BigDecimal latitude) {
        public SubjectInput {
            if (blank(domainCode) || blank(sampleName) || blank(regionCode)
                    || longitude == null || latitude == null) {
                throw new IllegalArgumentException("INVALID_SAMPLE_IDENTITY_INPUT");
            }
            domainCode = domainCode.trim().toUpperCase(Locale.ROOT);
            sampleName = sampleName.trim();
            sampleContact = sampleContact == null ? "" : sampleContact.trim();
            regionCode = regionCode.trim();
        }
    }

    public record Candidate(
            UUID samplePointId,
            String canonicalName,
            String sampleContact,
            String regionCode,
            BigDecimal longitude,
            BigDecimal latitude,
            int approvedRecordCount,
            LocalDate effectiveFrom) {
        public Candidate {
            if (samplePointId == null || blank(canonicalName) || blank(regionCode)
                    || longitude == null || latitude == null || approvedRecordCount < 0
                    || effectiveFrom == null) {
                throw new IllegalArgumentException("INVALID_SAMPLE_IDENTITY_CANDIDATE");
            }
            sampleContact = sampleContact == null ? "" : sampleContact.trim();
        }
    }
}
