package com.cofco.qiqihar.graintrade.testsupport;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Import(ProtectedTestDatabaseConfiguration.class)
@ActiveProfiles("test")
@TestExecutionListeners(
        listeners = ProtectedTestDatabaseExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
public @interface UsesProtectedTestDatabase {
}
