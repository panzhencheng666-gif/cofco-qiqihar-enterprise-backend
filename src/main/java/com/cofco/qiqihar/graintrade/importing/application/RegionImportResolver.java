package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.masterdata.importing.RegionImportDirectory;
import com.cofco.qiqihar.graintrade.masterdata.importing.RegionImportDirectory.RegionEntry;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
final class RegionImportResolver {
    private static final List<String> ADMINISTRATIVE_SUFFIXES = List.of(
            "特别行政区", "自治区", "自治州", "自治县", "自治旗", "林区", "特区",
            "地区", "盟", "州", "市", "区", "县", "旗");
    private static final Map<String, String> NAMED_ALIASES = Map.ofEntries(
            Map.entry("瑷珲", "231102"),
            Map.entry("瑷珲区", "231102"),
            Map.entry("梅里斯", "230208"),
            Map.entry("梅里斯区", "230208"));
    private final RegionImportDirectory regions;

    RegionImportResolver(RegionImportDirectory regions) {
        this.regions = regions;
    }

    String resolve(String input) {
        String value = input == null ? "" : input.trim();
        List<RegionEntry> entries = regions.regions();
        Map<String, RegionEntry> byCode = entries.stream()
                .collect(Collectors.toMap(RegionEntry::code, Function.identity()));
        if (byCode.containsKey(value)) return value;

        String normalized = normalizePath(value);
        List<String> matches = entries.stream()
                .filter(region -> path(region, byCode).equals(normalized))
                .map(RegionEntry::code)
                .toList();
        if (matches.size() == 1) return matches.getFirst();

        String namedCode = NAMED_ALIASES.get(value);
        if (namedCode != null && byCode.containsKey(namedCode)) return namedCode;

        List<String> aliasMatches = entries.stream()
                .filter(region -> aliases(region.name()).contains(value))
                .map(RegionEntry::code)
                .distinct()
                .toList();
        if (aliasMatches.size() == 1) return aliasMatches.getFirst();

        List<RegionEntry> embeddedMatches = entries.stream()
                .filter(region -> region.parentCode() != null)
                .filter(region -> matchesEmbeddedPath(value, region, byCode))
                .toList();
        int deepestMatch = embeddedMatches.stream()
                .mapToInt(region -> pathEntries(region, byCode).size())
                .max().orElse(0);
        List<String> mostSpecificCodes = embeddedMatches.stream()
                .filter(region -> pathEntries(region, byCode).size() == deepestMatch)
                .map(RegionEntry::code)
                .distinct()
                .toList();
        if (mostSpecificCodes.size() == 1) return mostSpecificCodes.getFirst();
        throw new ClientRequestException(
                "IMPORT_REGION_NOT_FOUND",
                "所在地区无法唯一识别；可填写常用简称、完整名称或路径、有效地区代码；存在同名时请补充上级地区");
    }

    String displayPath(String regionCode) {
        Map<String, RegionEntry> byCode = regions.regions().stream()
                .collect(Collectors.toMap(RegionEntry::code, Function.identity()));
        RegionEntry region = byCode.get(regionCode);
        if (region == null) {
            throw new ClientRequestException("IMPORT_REGION_NOT_FOUND", "所在地区无法识别");
        }
        return String.join(" / ", pathNames(region, byCode));
    }

    private static String path(RegionEntry region, Map<String, RegionEntry> byCode) {
        return String.join("/", pathNames(region, byCode));
    }

    private static List<String> pathNames(RegionEntry region, Map<String, RegionEntry> byCode) {
        return pathEntries(region, byCode).stream().map(RegionEntry::name).map(String::trim).toList();
    }

    private static List<RegionEntry> pathEntries(RegionEntry region, Map<String, RegionEntry> byCode) {
        java.util.ArrayDeque<RegionEntry> entries = new java.util.ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        RegionEntry cursor = region;
        while (cursor != null && visited.add(cursor.code())) {
            entries.addFirst(cursor);
            cursor = cursor.parentCode() == null ? null : byCode.get(cursor.parentCode());
        }
        return List.copyOf(entries);
    }

    private static boolean matchesEmbeddedPath(
            String value, RegionEntry region, Map<String, RegionEntry> byCode) {
        List<RegionEntry> path = pathEntries(region, byCode);
        if (path.size() < 2) return false;
        for (String rootAlias : aliases(path.getFirst())) {
            int start = value.indexOf(rootAlias);
            while (start >= 0) {
                if (matchesPathFrom(value, start + rootAlias.length(), path, 1)) return true;
                start = value.indexOf(rootAlias, start + 1);
            }
        }
        return false;
    }

    private static boolean matchesPathFrom(
            String value, int offset, List<RegionEntry> path, int pathIndex) {
        if (pathIndex >= path.size()) return true;
        for (String alias : aliases(path.get(pathIndex))) {
            if (value.startsWith(alias, offset)
                    && matchesPathFrom(value, offset + alias.length(), path, pathIndex + 1)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> aliases(RegionEntry region) {
        Set<String> aliases = new LinkedHashSet<>(aliases(region.name()));
        NAMED_ALIASES.forEach((alias, code) -> {
            if (region.code().equals(code)) aliases.add(alias);
        });
        return aliases;
    }

    private static String normalizePath(String value) {
        return java.util.Arrays.stream(value.split("\\s*(?:/|>|＞|→)\\s*", -1))
                .map(String::trim)
                .collect(Collectors.joining("/"));
    }

    private static Set<String> aliases(String officialName) {
        String name = officialName == null ? "" : officialName.trim();
        Set<String> aliases = new LinkedHashSet<>();
        if (name.isBlank()) return aliases;
        aliases.add(name);
        for (String suffix : ADMINISTRATIVE_SUFFIXES) {
            if (name.endsWith(suffix) && name.length() > suffix.length()) {
                aliases.add(name.substring(0, name.length() - suffix.length()));
            }
        }
        return aliases;
    }
}
