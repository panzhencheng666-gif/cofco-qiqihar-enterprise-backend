package com.cofco.qiqihar.graintrade.marketintelligence;
import static org.assertj.core.api.Assertions.*;
import java.io.IOException;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
class UnWebTvFeedTest {
    static final String ICS="""
        BEGIN:VCALENDAR
        BEGIN:VEVENT
        UID:TU123
        SUMMARY:Test
        URL:https://teamup.com/ksf6ikyw6jst7sqii1/events/123
        DTSTART:20260928T210000Z
        DTEND:20260928T220000Z
        END:VEVENT
        END:VCALENDAR
        """;
    static final String HTML="""
        <div class="un3-card-row-video"><h4><a href="https://webtv.un.org/en/asset/k1a/k1abcdefgh">Test</a></h4>
        <a class="ajax-popup-link" href="https://webtv.un.org/en/asset/k1a/k1abcdefgh"><img src="https://cfvod.kaltura.com/p/2503451/sp/250345100/thumbnail/entry_id/1_abcdefgh/width/100"></a>
        <div class="card-img-overlay"><span class="badge">Live</span></div><span class="mediaun-timezone">2026-09-28T21:00:00Z</span></div>
        """;
    @Test void completeSnapshotUsesOnlyFixedOfficialInputsAndBoundedSizes() throws Exception {
        var calls=new ArrayList<String>();
        var result=UnWebTvFeed.acquire((uri,limit)->{
            calls.add(uri+"|"+limit);return uri.getHost().equals("ics.teamup.com")?ICS:HTML;
        });
        assertThat(calls).containsExactly("https://ics.teamup.com/feed/ksf6ikyw6jst7sqii1/3807976.ics|8000000","https://webtv.un.org/en/schedule|2000000");
        assertThat(result.events()).hasSize(1);assertThat(result.programmes()).hasSize(1);
        assertThat(UnWebTvProgrammeParser.match(result.programmes().getFirst(),result.events())).isPresent();
    }
    @Test void malformedOrFailedSourceNeverProducesEmptySuccess() {
        assertThatThrownBy(()->UnWebTvFeed.acquire((uri,limit)->uri.getHost().equals("ics.teamup.com")?ICS:"maintenance" )).isInstanceOf(IOException.class);
        assertThatThrownBy(()->UnWebTvFeed.acquire((uri,limit)->{throw new IOException("fixture error");})).isInstanceOf(IOException.class);
    }
    @Test void oversizedBodyCancelsUpstreamWithoutReturningPartialBytes() {
        var body=new UnWebTvFeed.LimitedBody(3);
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        body.onSubscribe(new java.util.concurrent.Flow.Subscription(){
            public void request(long count) { }
            public void cancel() {cancelled.set(true);}
        });
        body.onNext(java.util.List.of(java.nio.ByteBuffer.wrap(new byte[]{1,2})));
        body.onNext(java.util.List.of(java.nio.ByteBuffer.wrap(new byte[]{3,4})));
        body.onComplete();
        assertThat(cancelled).isTrue();
        assertThatThrownBy(()->body.getBody().toCompletableFuture().join()).hasCauseInstanceOf(IOException.class);
    }
    @Test void exactBoundedBodyCompletes() {
        var body=new UnWebTvFeed.LimitedBody(3);
        body.onSubscribe(new java.util.concurrent.Flow.Subscription(){public void request(long count) { } public void cancel() { }});
        body.onNext(java.util.List.of(java.nio.ByteBuffer.wrap(new byte[]{1,2,3})));body.onComplete();
        assertThat(body.getBody().toCompletableFuture().join()).containsExactly((byte)1,(byte)2,(byte)3);
    }

}
