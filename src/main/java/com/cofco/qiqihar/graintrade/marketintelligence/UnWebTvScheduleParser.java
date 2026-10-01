package com.cofco.qiqihar.graintrade.marketintelligence;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Restricted parser for the public UN Web TV Teamup schedule; no playback claims. */
final class UnWebTvScheduleParser {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Set<String> FIELDS = Set.of("UID", "SUMMARY", "URL", "DTSTART", "DTEND", "LAST-MODIFIED", "STATUS");
    private static final Set<String> RECURRENCE = Set.of("RRULE", "RDATE", "EXDATE", "RECURRENCE-ID");
    record Event(String uid, String title, String url, Instant startsAt, Instant endsAt,
                 Instant modifiedAt, boolean cancelled) { }
    private record Property(String header, String value) { }

    static List<Event> parse(String source) throws IOException {
        if (source == null || source.length() > 8_000_000
                || source.getBytes(StandardCharsets.UTF_8).length > 8_000_000) throw invalid();
        var result = new ArrayList<Event>();
        var ids = new HashSet<String>();
        var stack = new ArrayDeque<String>();
        Map<String, Property> fields = null;
        boolean closed = false;
        for (String line : source.replace("\r\n", "\n").replaceAll("\n[ \\t]", "").split("\n")) {
            if (line.isEmpty()) continue;
            if (line.length() > 100_000 || line.indexOf('\r') >= 0 || closed) throw invalid();
            if (line.startsWith("BEGIN:")) {
                String component = line.substring(6);
                if (stack.isEmpty() && !component.equals("VCALENDAR")) throw invalid();
                if (component.equals("VEVENT")) {
                    if (stack.size() != 1 || fields != null) throw invalid();
                    fields = new HashMap<>();
                }
                if (stack.size() >= 8) throw invalid();
                stack.push(component);
            } else if (line.startsWith("END:")) {
                if (stack.isEmpty() || !stack.pop().equals(line.substring(4))) throw invalid();
                if (line.equals("END:VEVENT")) {
                    Event event = event(fields);
                    if (!ids.add(event.uid()) || result.size() >= 5000) throw invalid();
                    result.add(event);
                    fields = null;
                }
                if (stack.isEmpty()) closed = true;
            } else {
                if (stack.isEmpty()) throw invalid();
                if (!stack.peek().equals("VEVENT")) continue;
                int colon = line.indexOf(':');
                if (colon < 1) throw invalid();
                String header = line.substring(0, colon);
                String name = header.split(";", 2)[0];
                if (RECURRENCE.contains(name)) throw invalid();
                if (FIELDS.contains(name) && fields.putIfAbsent(name,
                        new Property(header, line.substring(colon + 1))) != null) throw invalid();
            }
        }
        if (!closed || !stack.isEmpty() || result.isEmpty()) throw invalid();
        return List.copyOf(result);
    }

    private static Event event(Map<String, Property> fields) throws IOException {
        String uid = required(fields, "UID").value();
        String title = text(required(fields, "SUMMARY").value()).strip();
        if (!uid.matches("TU[0-9]{1,20}") || title.isBlank() || title.length() > 1000) throw invalid();
        String url = required(fields, "URL").value();
        if (!url.equals("https://teamup.com/ksf6ikyw6jst7sqii1/events/" + uid.substring(2))) throw invalid();
        Instant start = time(required(fields, "DTSTART"));
        Instant end = time(required(fields, "DTEND"));
        Instant modified = fields.containsKey("LAST-MODIFIED") ? time(fields.get("LAST-MODIFIED")) : null;
        if (!end.isAfter(start)) throw invalid();
        String status = fields.containsKey("STATUS") ? fields.get("STATUS").value() : "";
        if (!Set.of("", "CONFIRMED", "TENTATIVE", "CANCELLED").contains(status)) throw invalid();
        return new Event(uid, title, url, start, end, modified, status.equals("CANCELLED"));
    }

    private static Property required(Map<String, Property> fields, String key) throws IOException {
        if (fields == null || !fields.containsKey(key)) throw invalid();
        return fields.get(key);
    }

    private static Instant time(Property property) throws IOException {
        try {
            String value = property.value();
            String[] header = property.header().split(";", -1);
            if (header.length == 1 && value.matches("[0-9]{8}T[0-9]{6}Z")) {
                return LocalDateTime.parse(value.substring(0, 15), TIME).toInstant(ZoneOffset.UTC);
            }
            if (header.length != 2 || !header[1].startsWith("TZID=")
                    || header[0].equals("LAST-MODIFIED") || !value.matches("[0-9]{8}T[0-9]{6}")) throw invalid();
            String zoneName = header[1].substring(5);
            if (zoneName.startsWith("\"") && zoneName.endsWith("\"")) zoneName = zoneName.substring(1, zoneName.length() - 1);
            if (!ZoneId.getAvailableZoneIds().contains(zoneName)) throw invalid();
            var local = LocalDateTime.parse(value, TIME);
            var offsets = ZoneId.of(zoneName).getRules().getValidOffsets(local);
            if (offsets.size() != 1) throw invalid();
            return local.toInstant(offsets.getFirst());
        } catch (RuntimeException invalidTime) {
            throw invalid();
        }
    }

    private static String text(String value) throws IOException {
        var decoded = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                if (++i == value.length()) throw invalid();
                c = value.charAt(i);
                if (c == 'n' || c == 'N') c = '\n';
                else if (c != '\\' && c != ',' && c != ';') throw invalid();
            }
            decoded.append(c);
        }
        return decoded.toString();
    }

    private static IOException invalid() {
        // Never include feed bodies or remote-provided values in diagnostics.
        return new IOException("Invalid or unsupported UN Web TV calendar");
    }
}
