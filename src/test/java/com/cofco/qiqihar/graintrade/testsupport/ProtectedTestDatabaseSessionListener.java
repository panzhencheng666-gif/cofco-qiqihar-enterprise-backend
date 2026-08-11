package com.cofco.qiqihar.graintrade.testsupport;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/** Makes every Maven test gate start from an empty, protected application schema set. */
public final class ProtectedTestDatabaseSessionListener implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        ProtectedTestDatabase.shared().resetForTestSession();
        System.out.println("Reset dedicated qiqihar_enterprise_test schemas for this test session.");
    }
}
