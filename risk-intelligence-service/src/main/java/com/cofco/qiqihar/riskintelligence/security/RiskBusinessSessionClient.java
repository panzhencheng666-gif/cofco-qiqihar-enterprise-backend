package com.cofco.qiqihar.riskintelligence.security;

import java.util.Optional;

@FunctionalInterface
public interface RiskBusinessSessionClient {
    Optional<RiskBusinessSession> authenticate(String cookieHeader);
}
