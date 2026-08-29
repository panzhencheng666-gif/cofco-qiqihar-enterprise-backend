package com.cofco.qiqihar.graintrade.identity.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IdentityDeliveryWorker {
    private final IdentityDeliveryOutboxRepository outbox;
    private final IdentityDeliveryGateway gateway;
    private final IdentityInvitationTokenCodec tokens;
    private final Clock clock;
    private final Duration lease;
    private final Duration baseRetry;
    private final Duration maximumRetry;
    private final boolean scheduledEnabled;
    private final MeterRegistry metrics;

    public IdentityDeliveryWorker(IdentityDeliveryOutboxRepository outbox,IdentityDeliveryGateway gateway,
            IdentityInvitationTokenCodec tokens,Clock clock,MeterRegistry metrics,
            @Value("${qiqihar.identity.delivery.lease:30s}") Duration lease,
            @Value("${qiqihar.identity.delivery.base-retry:10s}") Duration baseRetry,
            @Value("${qiqihar.identity.delivery.maximum-retry:15m}") Duration maximumRetry,
            @Value("${qiqihar.identity.delivery.worker-enabled:false}") boolean scheduledEnabled) {
        this.outbox=outbox;this.gateway=gateway;this.tokens=tokens;this.clock=clock;this.metrics=metrics;
        this.lease=positive(lease,"lease");this.baseRetry=positive(baseRetry,"baseRetry");
        this.maximumRetry=positive(maximumRetry,"maximumRetry");this.scheduledEnabled=scheduledEnabled;
    }

    @Scheduled(fixedDelayString="${qiqihar.identity.delivery.poll-delay:5s}")
    public void scheduledDrain() {
        if(!scheduledEnabled)return;
        outbox.expireInvitations(clock.instant());
        for(int index=0;index<20&&drainOne();index++) { }
    }

    public boolean drainOne() {
        var claimed=outbox.claimNext(clock.instant(),lease).orElse(null);
        if(claimed==null)return false;
        try {
            var payload=tokens.decryptDeliveryPayload(claimed.encryptedPayload());
            gateway.deliver(new IdentityDeliveryGateway.DeliveryCommand(
                    claimed.eventId(),claimed.invitationId(),claimed.subjectId(),
                    payload.deliveryAddress(),payload.token(),claimed.expiresAt()));
            outbox.markDelivered(claimed.eventId(),claimed.invitationId(),clock.instant());
            metrics.counter("qiqihar.identity.delivery","result","delivered").increment();
        } catch(RuntimeException failure) {
            Duration retry=boundedRetry(claimed.attemptCount());
            outbox.markFailed(claimed.eventId(),claimed.invitationId(),clock.instant().plus(retry),
                    safeCode(failure),"Identity delivery attempt failed");
            metrics.counter("qiqihar.identity.delivery","result","failed").increment();
        }
        return true;
    }

    private Duration boundedRetry(int attempt) {
        long multiplier=1L<<Math.min(20,Math.max(0,attempt-1));
        Duration retry;
        try{retry=baseRetry.multipliedBy(multiplier);}
        catch(ArithmeticException overflow){retry=maximumRetry;}
        return retry.compareTo(maximumRetry)>0?maximumRetry:retry;
    }

    private static String safeCode(RuntimeException failure) {
        String code=failure.getClass().getSimpleName();
        return code.length()>80?code.substring(0,80):code;
    }

    private static Duration positive(Duration value,String name) {
        if(value==null||value.isZero()||value.isNegative())
            throw new IllegalArgumentException(name+" must be positive");
        return value;
    }
}
