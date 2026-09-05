package com.cofco.qiqihar.graintrade.identity.application;

import java.util.List;
import java.util.UUID;

public final class RegionResponsibility {
    private RegionResponsibility() {}
    public record Region(String regionCode,String regionName,String subjectId,String displayName,long version) {}
    public record Sample(UUID id,String canonicalName,String regionCode,String regionName,
            String previousSubjectId,String previousDisplayName,String nextSubjectId,String nextDisplayName,long version) {}
    public record Preview(String subjectId,List<String> regionCodes,List<Region> regions,List<Sample> samples,String previewToken) {}
}
