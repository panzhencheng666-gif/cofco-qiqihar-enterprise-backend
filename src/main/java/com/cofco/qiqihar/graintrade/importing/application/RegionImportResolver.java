package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.masterdata.application.MasterDataRepository;
import com.cofco.qiqihar.graintrade.masterdata.domain.Region;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
final class RegionImportResolver {
    private final MasterDataRepository masterData;

    RegionImportResolver(MasterDataRepository masterData) {
        this.masterData = masterData;
    }

    String resolve(String input) {
        String value = input == null ? "" : input.trim();
        List<Region> regions = masterData.findRegions();
        Map<String, Region> byCode = regions.stream()
                .collect(Collectors.toMap(Region::code, Function.identity()));
        if (byCode.containsKey(value)) return value;

        String normalized = normalizePath(value);
        List<String> matches = regions.stream()
                .filter(region -> path(region, byCode).equals(normalized))
                .map(Region::code)
                .toList();
        if (matches.size() == 1) return matches.getFirst();
        throw new ClientRequestException(
                "IMPORT_REGION_NOT_FOUND",
                "所在地区必须填写完整行政区划路径；旧模板可继续填写有效地区代码");
    }

    private static String path(Region region, Map<String, Region> byCode) {
        java.util.ArrayDeque<String> names = new java.util.ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        Region cursor = region;
        while (cursor != null && visited.add(cursor.code())) {
            names.addFirst(cursor.name().trim());
            cursor = cursor.parentCode() == null ? null : byCode.get(cursor.parentCode());
        }
        return String.join("/", names);
    }

    private static String normalizePath(String value) {
        return java.util.Arrays.stream(value.split("\\s*(?:/|>|＞|→)\\s*", -1))
                .map(String::trim)
                .collect(Collectors.joining("/"));
    }
}
