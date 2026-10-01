package com.cofco.qiqihar.riskintelligence.assistant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Bounded baseline retrieval over approved, access-filtered, immutable snapshots. */
@Repository
public class AiKnowledgeRetriever {
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9]{2,}");
    private final JdbcClient jdbc;

    public AiKnowledgeRetriever(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<AiAssistantRepository.KnowledgeSource> retrieve(String question, boolean rootAccess) {
        List<String> terms = terms(question);
        if (terms.isEmpty()) return List.of();
        // Access is checked in SQL before any body reaches the private node.
        List<Snapshot> rows = jdbc.sql("""
                SELECT document_id,title,source_url,version,source_kind,body_text,
                       content_sha256,approved_at
                FROM risk.ai_knowledge_document
                WHERE status='APPROVED' AND body_text IS NOT NULL
                  AND (access_scope='BUSINESS' OR :root)
                ORDER BY approved_at DESC,document_id DESC
                LIMIT 500
                """).param("root", rootAccess).query(AiKnowledgeRetriever::map).list();
        return rows.stream()
                .map(row -> new Ranked(row, score(row, terms)))
                .filter(row -> row.score() > 0 && row.snapshot().url().length() <= 800)
                .sorted(Comparator.comparingInt(Ranked::score).reversed()
                        .thenComparing(row -> row.snapshot().id()))
                .limit(3)
                .map(row -> source(row.snapshot(), terms))
                .toList();
    }

    static List<String> terms(String question) {
        Set<String> terms = new LinkedHashSet<>();
        String normalized = question.toLowerCase(Locale.ROOT);
        Matcher words = WORD.matcher(normalized);
        while (words.find() && terms.size() < 16) terms.add(words.group());
        for (int i = 0; i + 1 < normalized.length() && terms.size() < 16; i++) {
            if (Character.UnicodeScript.of(normalized.charAt(i)) == Character.UnicodeScript.HAN &&
                    Character.UnicodeScript.of(normalized.charAt(i + 1)) == Character.UnicodeScript.HAN) {
                terms.add(normalized.substring(i, i + 2));
            }
        }
        return new ArrayList<>(terms);
    }

    private static int score(Snapshot row, List<String> terms) {
        String title = row.title().toLowerCase(Locale.ROOT);
        String body = row.body().toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (title.contains(term)) score += 5;
            if (body.contains(term)) score++;
        }
        return score;
    }

    private static AiAssistantRepository.KnowledgeSource source(Snapshot row, List<String> terms) {
        String lower = row.body().toLowerCase(Locale.ROOT);
        int first = -1;
        for (String term : terms) {
            int index = lower.indexOf(term);
            if (index >= 0 && (first < 0 || index < first)) first = index;
        }
        int start = Math.max(0, first - 350);
        int end = Math.min(row.body().length(), start + 2400);
        String excerpt = row.body().substring(start, end);
        LocalDate verified = row.approvedAt().atZone(ZoneOffset.UTC).toLocalDate();
        return new AiAssistantRepository.KnowledgeSource(
                row.id(), row.title(), row.url(), verified.toString(), excerpt,
                row.sha256(), row.kind(), row.version(), "RETRIEVAL_ONLY");
    }

    private static Snapshot map(ResultSet row, int index) throws SQLException {
        return new Snapshot(row.getString("document_id"), row.getString("title"),
                row.getString("source_url"), row.getInt("version"),
                row.getString("source_kind"), row.getString("body_text"),
                row.getString("content_sha256"), row.getTimestamp("approved_at").toInstant());
    }

    private record Snapshot(String id, String title, String url, int version, String kind,
            String body, String sha256, java.time.Instant approvedAt) { }
    private record Ranked(Snapshot snapshot, int score) { }
}
