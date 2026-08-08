package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class BusinessReadAuthenticationInterceptorTest {

    @Test
    void productionConstructorCannotDisableReadAuthentication() {
        AccessControl accessControl = mock(AccessControl.class);
        BusinessReadAuthenticationInterceptor interceptor =
                new BusinessReadAuthenticationInterceptor(accessControl, false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/market-records");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        verify(accessControl).require("BUSINESS_READ", null);
    }
}
