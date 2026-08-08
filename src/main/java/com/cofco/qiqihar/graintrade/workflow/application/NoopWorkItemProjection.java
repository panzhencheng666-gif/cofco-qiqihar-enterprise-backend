package com.cofco.qiqihar.graintrade.workflow.application;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Production/cloud profiles keep workflow projections owned by their workflow integration. */
@Component
@Profile("!local")
public class NoopWorkItemProjection implements WorkItemProjection {
    @Override
    public void refresh() {
        // Deliberately empty: local projection must never run in a production profile.
    }
}
