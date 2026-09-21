package com.cofco.qiqihar.graintrade.identity.application;

import org.springframework.jdbc.core.simple.JdbcClient;

/** One lock order for region changes, phone bindings and invitations. */
public final class PhoneIdentityLock {
    private PhoneIdentityLock() {}
    public static void acquire(JdbcClient jdbc) {
        jdbc.sql("SELECT platform.lock_region_responsibility_change()").query(Object.class).single();
        jdbc.sql("SELECT pg_advisory_xact_lock(184,185)").query(Object.class).single();
        jdbc.sql("LOCK TABLE platform.phone_identity IN SHARE ROW EXCLUSIVE MODE").update();
    }
}
