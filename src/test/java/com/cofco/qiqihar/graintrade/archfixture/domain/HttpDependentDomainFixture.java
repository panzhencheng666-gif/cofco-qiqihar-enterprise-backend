package com.cofco.qiqihar.graintrade.archfixture.domain;

import java.net.URI;

public final class HttpDependentDomainFixture {

    private final URI endpoint;

    public HttpDependentDomainFixture(URI endpoint) {
        this.endpoint = endpoint;
    }

    public URI endpoint() {
        return endpoint;
    }
}
