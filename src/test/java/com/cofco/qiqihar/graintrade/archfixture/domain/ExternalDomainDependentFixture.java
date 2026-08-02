package com.cofco.qiqihar.graintrade.archfixture.domain;

import com.external.framework.domain.ExternalDomainType;

public final class ExternalDomainDependentFixture {

    private final ExternalDomainType dependency;

    public ExternalDomainDependentFixture(ExternalDomainType dependency) {
        this.dependency = dependency;
    }

    public ExternalDomainType dependency() {
        return dependency;
    }
}
