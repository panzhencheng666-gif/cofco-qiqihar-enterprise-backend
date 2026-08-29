package com.cofco.qiqihar.graintrade.identity.application;

import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.util.UUID;

public interface IdentityGovernanceRepository {
    boolean validAssignment(EmployeeAssignment assignment);
    boolean exists(String subjectId);
    EmployeeProfile create(String subjectId,EmployeeAssignment assignment,String actor);
    Optional<EmployeeProfile> find(String subjectId,String workUnitCode);
    List<EmployeeProfile> findAll(String workUnitCode);
    Optional<EmployeeProfile> update(String subjectId,long expectedVersion,EmployeeAssignment assignment,String actor);
    AssignmentOptions assignmentOptions(String workUnitCode);
    Optional<IdentityInvitation> findInvitationByIdempotency(String actorSubjectId,String idempotencyKey);
    Optional<IdentityInvitation> findInvitation(UUID invitationId);
    Optional<IdentityInvitation> revokeInvitation(UUID invitationId,Instant revokedAt);
    void revokePendingInvitations(String subjectId,Instant revokedAt);
    IdentityInvitation createInvitation(UUID invitationId,String subjectId,String tokenSha256,
            String encryptedDeliveryPayload,String deliveryAddressSha256,Instant expiresAt,
            String actorSubjectId,String idempotencyKey,String requestSha256);
    Optional<EmployeeProfile> activateInvitation(String tokenSha256,String issuerUri,
            String providerSubject,Instant activatedAt);
}
