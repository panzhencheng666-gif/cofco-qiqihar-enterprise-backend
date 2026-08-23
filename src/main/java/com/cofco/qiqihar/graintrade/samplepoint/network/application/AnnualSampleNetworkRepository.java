package com.cofco.qiqihar.graintrade.samplepoint.network.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface AnnualSampleNetworkRepository {
    List<DesignSamplePointView> designPoints(String regionCode, Set<String> authorizedRegions);

    Optional<AnnualSampleNetworkView> find(int year, Set<String> authorizedRegions);

    SampleNetworkComparisonView comparison(
            int year, String regionCode, String productCode, Set<String> authorizedRegions);

    boolean exists(int year);

    boolean isPublished(int year);

    boolean knownProduct(String productCode);

    Optional<SamplePointLocation> samplePointLocation(UUID samplePointId, int networkYear);

    boolean canGovernNetwork(int year, Set<String> authorizedRegions);

    boolean lockDraft(int year);

    boolean lockInReview(int year);

    void create(int year, Integer carriedFromYear, String actor, Instant now);

    MembershipWriteResult upsertMembership(
            int year, UUID samplePointId, String designVillageRegionCode,
            String relationType, String evidenceReference, String statusCode,
            String sourceCode, String reason, long version, String actor, Instant now);

    int submit(int year, long version, String actor, Instant now);

    int approve(int year, long version, String actor, String reason, Instant now);

    int returnToDraft(int year, long version, String actor, String reason, Instant now);

    record SamplePointLocation(String regionCode, String regionLevel) {}

    record MembershipWriteResult(int membershipChanges, int relationChanges) {}
}
