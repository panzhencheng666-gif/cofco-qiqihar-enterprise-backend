package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class SecurityStartupInvariantTest {

    @Test
    void rejectsLocalProfileOnWildcardAddress() {
        assertThatThrownBy(() -> SecurityStartupInvariant.validate(
                Set.of("local"), "0.0.0.0", "X-Actor", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void rejectsTrustedSubjectHeaderOutsideLocalProfile() {
        assertThatThrownBy(() -> SecurityStartupInvariant.validate(
                Set.of("production"), "127.0.0.1", "X-Actor", "https://id.example.test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trusted-subject-header")
                .hasMessageContaining("local");
    }

    @Test
    void rejectsMissingOidcIssuerInProduction() {
        assertThatThrownBy(() -> SecurityStartupInvariant.validate(
                Set.of("production"), "127.0.0.1", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QIQIHAR_OIDC_ISSUER_URI");
    }

    @Test
    void rejectsIncompleteOidcBrowserClientInProduction() {
        assertThatThrownBy(() -> SecurityStartupInvariant.validate(
                Set.of("production"), "127.0.0.1", "", "https://id.example.test",
                "", "", "", "", "mfa", "", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QIQIHAR_OIDC_CLIENT_ID");
        assertThatThrownBy(() -> SecurityStartupInvariant.validate(
                Set.of("production"), "127.0.0.1", "", "https://id.example.test",
                "client", "", "", "", "mfa", "", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QIQIHAR_OIDC_CLIENT_SECRET");
        assertThatThrownBy(() -> SecurityStartupInvariant.validate(
                Set.of("production"), "127.0.0.1", "", "https://id.example.test",
                "client", "secret", "", "", "mfa", "", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QIQIHAR_OIDC_REDIRECT_URI");
    }

    @Test
    void rejectsCallbackOutsideTheControlledEnterpriseEndpoint() {
        assertThatThrownBy(() -> SecurityStartupInvariant.validate(
                Set.of("production"), "127.0.0.1", "", "https://id.example.test",
                "client", "secret", "https://app.example.test/uncontrolled", "", "mfa", "", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/login/oauth2/code/enterprise");
    }

    @Test
    void rejectsProductionWithoutApprovedMfaEvidenceContract() {
        assertThatThrownBy(() -> SecurityStartupInvariant.validate(
                Set.of("production"), "127.0.0.1", "", "https://id.example.test",
                "client", "secret", "https://app.example.test/login/oauth2/code/enterprise",
                "https://app.example.test/logged-out", "", "", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MFA");
    }

    @Test
    void acceptsCompleteProductionBrowserSessionContract() {
        assertThatCode(() -> SecurityStartupInvariant.validate(
                Set.of("production"), "127.0.0.1", "", "https://id.example.test",
                "client", "secret", "https://app.example.test/login/oauth2/code/enterprise",
                "https://app.example.test/logged-out", "mfa", "", true))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsProductionWithoutControlledPostLogoutRedirect() {
        assertThatThrownBy(() -> SecurityStartupInvariant.validate(
                Set.of("production"), "127.0.0.1", "", "https://id.example.test",
                "client", "secret", "https://app.example.test/login/oauth2/code/enterprise",
                "", "mfa", "", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QIQIHAR_OIDC_POST_LOGOUT_REDIRECT_URI");
    }

    @Test
    void acceptsLocalProfileOnLoopbackWithDevelopmentActorHeader() {
        assertThatCode(() -> SecurityStartupInvariant.validate(
                Set.of("local"), "127.0.0.1", "X-Actor", ""))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsHostnameEvenWhenItUsuallyAliasesLoopback() {
        assertThatThrownBy(() -> SecurityStartupInvariant.validate(
                Set.of("local"), "localhost", "X-Actor", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("numeric loopback");
    }

    @Test
    void acceptsNumericIpv4AndIpv6LoopbackAddresses() {
        assertThatCode(() -> SecurityStartupInvariant.validate(
                Set.of("local"), "127.0.0.2", "X-Actor", ""))
                .doesNotThrowAnyException();
        assertThatCode(() -> SecurityStartupInvariant.validate(
                Set.of("local"), "::1", "X-Actor", ""))
                .doesNotThrowAnyException();
    }

    @Test
    void productionIdentityLifecycleConfigurationFailsClosed() {
        String key="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        assertThatThrownBy(() -> completeIdentityProduction("",false,true,"https://id.example.test/deliver",
                "credential","https://app.example.test/activate","https://id.example.test/account"))
                .hasMessageContaining("QIQIHAR_IDENTITY_INVITATION_ENCRYPTION_KEY");
        assertThatThrownBy(() -> completeIdentityProduction(key,true,true,"https://id.example.test/deliver",
                "credential","https://app.example.test/activate","https://id.example.test/account"))
                .hasMessageContaining("self-registration");
        assertThatThrownBy(() -> completeIdentityProduction(key,false,false,"https://id.example.test/deliver",
                "credential","https://app.example.test/activate","https://id.example.test/account"))
                .hasMessageContaining("DELIVERY_WORKER_ENABLED");
        assertThatThrownBy(() -> completeIdentityProduction(key,false,true,"",
                "credential","https://app.example.test/activate","https://id.example.test/account"))
                .hasMessageContaining("QIQIHAR_IDENTITY_DELIVERY_ENDPOINT");
        assertThatThrownBy(() -> completeIdentityProduction(key,false,true,"https://id.example.test/deliver",
                "credential","https://app.example.test/activate",""))
                .hasMessageContaining("QIQIHAR_IDENTITY_MANAGEMENT_URL");
        assertThatCode(() -> completeIdentityProduction(key,false,true,"https://id.example.test/deliver",
                "credential","https://app.example.test/activate","https://id.example.test/account"))
                .doesNotThrowAnyException();
    }

    private static void completeIdentityProduction(String encryptionKey,boolean selfRegistration,
            boolean workerEnabled,String deliveryEndpoint,String deliveryCredential,
            String activationUrl,String identityManagementUrl) {
        SecurityStartupInvariant.validate(Set.of("production"),"127.0.0.1","",
                "https://id.example.test","client","secret",
                "https://app.example.test/login/oauth2/code/enterprise",
                "https://app.example.test/logged-out","mfa","",true,
                encryptionKey,selfRegistration,workerEnabled,deliveryEndpoint,deliveryCredential,
                activationUrl,identityManagementUrl);
    }

    @Test
    void rejectsTestProfileWithoutTheTestClasspathMarker() {
        assertThatThrownBy(() -> SecurityStartupInvariant.validate(
                Set.of("test"), "127.0.0.1", "", "", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test profile")
                .hasMessageContaining("production artifact");
    }
}
