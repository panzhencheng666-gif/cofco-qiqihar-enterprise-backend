package com.cofco.qiqihar.graintrade.notification.infrastructure;

import com.cofco.qiqihar.graintrade.notification.application.BusinessEventDeliveryBacklog;
import com.cofco.qiqihar.graintrade.notification.application.BusinessEventDeliveryRepository;
import com.cofco.qiqihar.graintrade.notification.application.BusinessNotification;
import com.cofco.qiqihar.graintrade.shared.security.application.AuthorizedReadScope;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcBusinessEventDeliveryRepository implements BusinessEventDeliveryRepository {
    private final JdbcClient jdbc;

    public JdbcBusinessEventDeliveryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void ensureCheckpoint(String consumerId, String instanceId, long initialSequence, Instant now) {
        jdbc.sql("""
                INSERT INTO platform.business_event_delivery_checkpoint(
                  consumer_id,initial_sequence,last_observed_sequence,last_delivered_sequence,
                  last_instance_id,created_at,updated_at)
                VALUES(:consumerId,:initialSequence,:initialSequence,:initialSequence,
                  :instanceId,:now,:now)
                ON CONFLICT(consumer_id) DO UPDATE SET
                  last_observed_sequence=GREATEST(
                    platform.business_event_delivery_checkpoint.last_observed_sequence,:initialSequence),
                  last_instance_id=:instanceId,updated_at=:now
                """).param("consumerId", consumerId).param("initialSequence", initialSequence)
                .param("instanceId", instanceId).param("now", Timestamp.from(now)).update();
    }

    @Override
    public Optional<Instant> pollRetryAt(String consumerId) {
        return jdbc.sql("""
                SELECT poll_next_retry_at
                FROM platform.business_event_delivery_checkpoint
                WHERE consumer_id=:consumerId AND poll_next_retry_at IS NOT NULL
                """).param("consumerId", consumerId).query(Timestamp.class).optional()
                .map(Timestamp::toInstant);
    }

    @Override
    @Transactional
    public void recordPollSucceeded(
            String consumerId, String instanceId, long afterSequence, Instant now) {
        Optional<Integer> priorFailures = jdbc.sql("""
                SELECT consecutive_poll_failures
                FROM platform.business_event_delivery_checkpoint
                WHERE consumer_id=:consumerId
                FOR UPDATE
                """).param("consumerId", consumerId).query(Integer.class).optional();
        if (priorFailures.isEmpty()) return;
        jdbc.sql("""
                UPDATE platform.business_event_delivery_checkpoint
                SET consecutive_poll_failures=0,poll_next_retry_at=NULL,
                    last_poll_failure_code=NULL,last_poll_failure_message=NULL,
                    last_instance_id=:instanceId,updated_at=:now
                WHERE consumer_id=:consumerId
                """).param("consumerId", consumerId).param("instanceId", instanceId)
                .param("now", Timestamp.from(now)).update();
        if (priorFailures.get() == 0) return;
        jdbc.sql("""
                INSERT INTO platform.business_event_poll_attempt(
                  consumer_id,instance_id,after_sequence,attempt_no,status_code,
                  attempted_at,completed_at)
                VALUES(:consumerId,:instanceId,:afterSequence,:attemptNo,'SUCCEEDED',:now,:now)
                """).param("consumerId", consumerId).param("instanceId", instanceId)
                .param("afterSequence", afterSequence).param("attemptNo", priorFailures.get() + 1)
                .param("now", Timestamp.from(now)).update();
    }

    @Override
    @Transactional
    public Duration recordPollFailed(
            String consumerId,
            String instanceId,
            long afterSequence,
            String failureCode,
            String failureMessage,
            Instant now,
            Duration baseRetry,
            Duration maximumRetry) {
        int attemptNo = jdbc.sql("""
                UPDATE platform.business_event_delivery_checkpoint
                SET consecutive_poll_failures=consecutive_poll_failures+1,
                    last_poll_failure_code=:failureCode,last_poll_failure_message=:failureMessage,
                    last_instance_id=:instanceId,updated_at=:now
                WHERE consumer_id=:consumerId
                RETURNING consecutive_poll_failures
                """).param("consumerId", consumerId).param("instanceId", instanceId)
                .param("failureCode", failureCode).param("failureMessage", failureMessage)
                .param("now", Timestamp.from(now)).query(Integer.class).single();
        Duration retry = boundedRetry(baseRetry, maximumRetry, attemptNo);
        Instant nextRetryAt = now.plus(retry);
        jdbc.sql("""
                UPDATE platform.business_event_delivery_checkpoint
                SET poll_next_retry_at=:nextRetryAt,updated_at=:now
                WHERE consumer_id=:consumerId
                """).param("consumerId", consumerId).param("nextRetryAt", Timestamp.from(nextRetryAt))
                .param("now", Timestamp.from(now)).update();
        jdbc.sql("""
                INSERT INTO platform.business_event_poll_attempt(
                  consumer_id,instance_id,after_sequence,attempt_no,status_code,
                  attempted_at,completed_at,next_retry_at,failure_code,failure_message)
                VALUES(:consumerId,:instanceId,:afterSequence,:attemptNo,'RETRY_SCHEDULED',
                  :now,:now,:nextRetryAt,:failureCode,:failureMessage)
                """).param("consumerId", consumerId).param("instanceId", instanceId)
                .param("afterSequence", afterSequence).param("attemptNo", attemptNo)
                .param("now", Timestamp.from(now)).param("nextRetryAt", Timestamp.from(nextRetryAt))
                .param("failureCode", failureCode).param("failureMessage", failureMessage).update();
        return retry;
    }

    @Override
    @Transactional
    public ClaimDecision claim(
            String consumerId,
            String instanceId,
            BusinessNotification event,
            Instant now,
            Instant leaseUntil) {
        UUID leaseToken = UUID.randomUUID();
        jdbc.sql("""
                UPDATE platform.business_event_delivery_checkpoint
                SET last_observed_sequence=GREATEST(last_observed_sequence,:eventSequence),
                    last_instance_id=:instanceId,updated_at=:now
                WHERE consumer_id=:consumerId
                """).param("consumerId", consumerId).param("eventSequence", event.sequence())
                .param("instanceId", instanceId).param("now", Timestamp.from(now)).update();
        Optional<Integer> attemptNo = jdbc.sql("""
                INSERT INTO platform.business_event_delivery_state(
                  consumer_id,event_id,event_sequence,status_code,attempt_count,
                  lease_owner,lease_token,lease_until,updated_at)
                VALUES(:consumerId,:eventId,:eventSequence,'IN_PROGRESS',1,
                  :instanceId,:leaseToken,:leaseUntil,:now)
                ON CONFLICT(consumer_id,event_id) DO UPDATE SET
                  status_code='IN_PROGRESS',attempt_count=platform.business_event_delivery_state.attempt_count+1,
                  lease_owner=:instanceId,lease_token=:leaseToken,lease_until=:leaseUntil,
                  next_retry_at=NULL,delivered_at=NULL,quarantined_at=NULL,updated_at=:now
                WHERE (platform.business_event_delivery_state.status_code='RETRY_SCHEDULED'
                         AND platform.business_event_delivery_state.next_retry_at<=:now)
                   OR (platform.business_event_delivery_state.status_code='IN_PROGRESS'
                         AND platform.business_event_delivery_state.lease_until<=:now)
                RETURNING attempt_count
                """).param("consumerId", consumerId).param("eventId", event.id())
                .param("eventSequence", event.sequence()).param("instanceId", instanceId)
                .param("leaseToken", leaseToken).param("leaseUntil", Timestamp.from(leaseUntil))
                .param("now", Timestamp.from(now)).query(Integer.class).optional();
        if (attemptNo.isEmpty()) {
            String status = jdbc.sql("""
                    SELECT status_code FROM platform.business_event_delivery_state
                    WHERE consumer_id=:consumerId AND event_id=:eventId
                    """).param("consumerId", consumerId).param("eventId", event.id())
                    .query(String.class).single();
            ClaimState state = switch (status) {
                case "DELIVERED" -> ClaimState.DELIVERED;
                case "QUARANTINED" -> ClaimState.QUARANTINED;
                default -> ClaimState.DEFERRED;
            };
            return ClaimDecision.skipped(state);
        }
        jdbc.sql("""
                INSERT INTO platform.business_event_delivery_attempt(
                  consumer_id,event_id,event_sequence,attempt_no,instance_id,lease_token,
                  status_code,started_at)
                VALUES(:consumerId,:eventId,:eventSequence,:attemptNo,:instanceId,:leaseToken,
                  'IN_PROGRESS',:now)
                """).param("consumerId", consumerId).param("eventId", event.id())
                .param("eventSequence", event.sequence()).param("attemptNo", attemptNo.get())
                .param("instanceId", instanceId).param("leaseToken", leaseToken)
                .param("now", Timestamp.from(now)).update();
        return ClaimDecision.claimed(new DeliveryClaim(consumerId, instanceId, event.id(),
                event.sequence(), attemptNo.get(), leaseToken));
    }

    @Override
    @Transactional
    public boolean markDelivered(DeliveryClaim claim, Instant deliveredAt) {
        int updated = jdbc.sql("""
                UPDATE platform.business_event_delivery_state
                SET status_code='DELIVERED',lease_owner=NULL,lease_token=NULL,lease_until=NULL,
                    next_retry_at=NULL,delivered_at=:deliveredAt,quarantined_at=NULL,
                    last_failure_code=NULL,last_failure_message=NULL,updated_at=:deliveredAt
                WHERE consumer_id=:consumerId AND event_id=:eventId
                  AND status_code='IN_PROGRESS' AND lease_token=:leaseToken
                """).param("consumerId", claim.consumerId()).param("eventId", claim.eventId())
                .param("leaseToken", claim.leaseToken())
                .param("deliveredAt", Timestamp.from(deliveredAt)).update();
        if (updated == 0) return false;
        jdbc.sql("""
                UPDATE platform.business_event_delivery_attempt
                SET status_code='DELIVERED',completed_at=:deliveredAt
                WHERE consumer_id=:consumerId AND event_id=:eventId AND attempt_no=:attemptNo
                  AND lease_token=:leaseToken AND status_code='IN_PROGRESS'
                """).param("consumerId", claim.consumerId()).param("eventId", claim.eventId())
                .param("attemptNo", claim.attemptNo()).param("leaseToken", claim.leaseToken())
                .param("deliveredAt", Timestamp.from(deliveredAt)).update();
        jdbc.sql("""
                UPDATE platform.business_event_delivery_checkpoint
                SET last_delivered_sequence=GREATEST(last_delivered_sequence,:eventSequence),
                    delivered_count=delivered_count+1,last_instance_id=:instanceId,updated_at=:deliveredAt
                WHERE consumer_id=:consumerId
                """).param("consumerId", claim.consumerId()).param("instanceId", claim.instanceId())
                .param("eventSequence", claim.eventSequence())
                .param("deliveredAt", Timestamp.from(deliveredAt)).update();
        return true;
    }

    @Override
    @Transactional
    public boolean markFailed(
            DeliveryClaim claim,
            String failureCode,
            String failureMessage,
            Instant failedAt,
            Instant nextRetryAt,
            boolean quarantine) {
        String status = quarantine ? "QUARANTINED" : "RETRY_SCHEDULED";
        int updated = jdbc.sql("""
                UPDATE platform.business_event_delivery_state
                SET status_code=:status,lease_owner=NULL,lease_token=NULL,lease_until=NULL,
                    next_retry_at=:nextRetryAt,delivered_at=NULL,quarantined_at=:quarantinedAt,
                    last_failure_code=:failureCode,last_failure_message=:failureMessage,updated_at=:failedAt
                WHERE consumer_id=:consumerId AND event_id=:eventId
                  AND status_code='IN_PROGRESS' AND lease_token=:leaseToken
                """).param("status", status).param("consumerId", claim.consumerId())
                .param("eventId", claim.eventId()).param("leaseToken", claim.leaseToken())
                .param("nextRetryAt", timestamp(nextRetryAt))
                .param("quarantinedAt", quarantine ? Timestamp.from(failedAt) : null)
                .param("failureCode", failureCode).param("failureMessage", failureMessage)
                .param("failedAt", Timestamp.from(failedAt)).update();
        if (updated == 0) return false;
        jdbc.sql("""
                UPDATE platform.business_event_delivery_attempt
                SET status_code=:status,completed_at=:failedAt,next_retry_at=:nextRetryAt,
                    failure_code=:failureCode,failure_message=:failureMessage
                WHERE consumer_id=:consumerId AND event_id=:eventId AND attempt_no=:attemptNo
                  AND lease_token=:leaseToken AND status_code='IN_PROGRESS'
                """).param("status", status).param("consumerId", claim.consumerId())
                .param("eventId", claim.eventId()).param("attemptNo", claim.attemptNo())
                .param("leaseToken", claim.leaseToken()).param("failedAt", Timestamp.from(failedAt))
                .param("nextRetryAt", timestamp(nextRetryAt)).param("failureCode", failureCode)
                .param("failureMessage", failureMessage).update();
        if (quarantine) {
            jdbc.sql("""
                    UPDATE platform.business_event_delivery_checkpoint
                    SET quarantined_count=quarantined_count+1,last_instance_id=:instanceId,
                        updated_at=:failedAt
                    WHERE consumer_id=:consumerId
                    """).param("consumerId", claim.consumerId()).param("instanceId", claim.instanceId())
                    .param("failedAt", Timestamp.from(failedAt)).update();
        }
        return true;
    }

    @Override
    public BusinessEventDeliveryBacklog backlog(
            String consumerId, AuthorizedReadScope scope, long afterSequence) {
        if (!scope.isUnrestricted() && scope.regionCodes().isEmpty()) {
            return new BusinessEventDeliveryBacklog(0, 0, 0, 0, null);
        }
        String authorization = scope.isUnrestricted()
                ? ""
                : " AND EXISTS (SELECT 1 FROM unnest(event.region_codes) region_code"
                        + " WHERE region_code IN (:authorizedRegions))";
        JdbcClient.StatementSpec statement = jdbc.sql("""
                SELECT
                  count(*) FILTER (WHERE state.status_code IS NULL
                    OR state.status_code IN ('IN_PROGRESS','RETRY_SCHEDULED')) AS pending_count,
                  count(*) FILTER (WHERE state.status_code='RETRY_SCHEDULED') AS retry_count,
                  count(*) FILTER (WHERE state.status_code='IN_PROGRESS') AS in_progress_count,
                  count(*) FILTER (WHERE state.status_code='QUARANTINED') AS quarantined_count,
                  min(event.occurred_at) FILTER (WHERE state.status_code IS NULL
                    OR state.status_code IN ('IN_PROGRESS','RETRY_SCHEDULED')) AS oldest_pending_at
                FROM platform.business_event_outbox event
                LEFT JOIN platform.business_event_delivery_state state
                  ON state.consumer_id=:consumerId AND state.event_id=event.event_id
                WHERE event.event_sequence>:afterSequence
                """ + authorization).param("consumerId", consumerId)
                .param("afterSequence", afterSequence);
        statement = bindRegions(statement, scope.regionCodes(), scope.isUnrestricted());
        Map<String, Object> row = statement.query().singleRow();
        Timestamp oldest = (Timestamp) row.get("oldest_pending_at");
        return new BusinessEventDeliveryBacklog(number(row, "pending_count"),
                number(row, "retry_count"), number(row, "in_progress_count"),
                number(row, "quarantined_count"), oldest == null ? null : oldest.toInstant());
    }

    private static Duration boundedRetry(Duration base, Duration maximum, int attemptNo) {
        long multiplier = 1L << Math.min(30, Math.max(0, attemptNo - 1));
        try {
            Duration retry = base.multipliedBy(multiplier);
            return retry.compareTo(maximum) > 0 ? maximum : retry;
        } catch (ArithmeticException overflow) {
            return maximum;
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static JdbcClient.StatementSpec bindRegions(
            JdbcClient.StatementSpec statement, Set<String> regionCodes, boolean unrestricted) {
        return unrestricted ? statement : statement.param("authorizedRegions", regionCodes);
    }
}
