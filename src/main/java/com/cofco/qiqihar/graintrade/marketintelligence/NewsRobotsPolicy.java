package com.cofco.qiqihar.graintrade.marketintelligence;
import java.net.URI;
import java.time.Instant;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.util.List;
import java.util.Locale;
import crawlercommons.robots.SimpleRobotRulesParser;

/** Robots eligibility only; not a copyright, republication, or media-playback authorization. */
final class NewsRobotsPolicy {
    record Decision(boolean allowed, String reason, long crawlDelayMillis) {}
    static Decision evaluate(URI article, URI robots, int status, byte[] body, String contentType,
                             Instant retrievedAt, Instant now) {
        var articleUri=article==null?null:NewsSearchResults.candidateUri(article.toString());
        var robotsUri=robots==null?null:NewsSearchResults.candidateUri(robots.toString());
        if(articleUri==null || robotsUri==null || !articleUri.getHost().equals(robotsUri.getHost())
                || !"/robots.txt".equals(robotsUri.getRawPath()) || robotsUri.getRawQuery()!=null
                || robots.getRawFragment()!=null)
            return denied("ROBOTS_SCOPE_MISMATCH");
        if(retrievedAt==null || now==null || retrievedAt.isAfter(now)
                || !retrievedAt.plusSeconds(86400).isAfter(now))
            return denied("ROBOTS_STALE");
        if(status==404 || status==410) return new Decision(true,"ROBOTS_ABSENT",0);
        // More conservative than the RFC's optional access on other 4xx statuses.
        if(status!=200) return denied("ROBOTS_UNAVAILABLE");
        if(body==null || body.length>512000 || contentType==null
                || !"text/plain".equals(contentType.split(";",2)[0].strip().toLowerCase(Locale.ROOT)))
            return denied("ROBOTS_INVALID");
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            String start=text.stripLeading().toLowerCase(Locale.ROOT);
            if(text.isBlank()) return new Decision(true,"ROBOTS_ALLOWED",0);
            if(start.startsWith("<") || text.indexOf('\u0000')>=0) return denied("ROBOTS_INVALID");
            var parser=new SimpleRobotRulesParser();
            parser.setExactUserAgentMatching(true);
            parser.setMaxWarnings(0); // Do not log uncontrolled robots content.
            var rules=parser.parseContent(robotsUri.toString(),body,"text/plain",List.of("qiliangnewsdiscovery"));
            if(parser.getNumWarnings()>0) return denied("ROBOTS_INVALID");
            boolean allowed=rules.isAllowed(articleUri.toASCIIString());
            return new Decision(allowed,allowed?"ROBOTS_ALLOWED":"ROBOTS_DISALLOWED",
                Math.max(0,rules.getCrawlDelay()));
        } catch(java.nio.charset.CharacterCodingException | RuntimeException invalid) {
            return denied("ROBOTS_INVALID");
        }
    }
    private static Decision denied(String reason) { return new Decision(false,reason,0); }
}
