package com.cofco.qiqihar.graintrade.risk.application;

import java.time.Clock;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="qiqihar.risk.training.enabled",matchIfMissing=true)
public class RiskLlmModelProvisioner implements ApplicationRunner {
    private final RiskTrainingRepository repository;
    private final Clock clock;
    private final String endpoint;
    private final String baseModelReference;
    private final boolean remoteNodeEnabled;
    private final String remoteNodeToken;

    public RiskLlmModelProvisioner(RiskTrainingRepository repository,Clock clock,
            @Value("${qiqihar.risk.training.llm.endpoint:}") String endpoint,
            @Value("${qiqihar.risk.training.llm.base-model-reference:}") String baseModelReference,
            @Value("${qiqihar.risk.training.remote-node.enabled:false}")
            boolean remoteNodeEnabled,
            @Value("${qiqihar.risk.training.remote-node.token:}") String remoteNodeToken) {
        this.repository=repository;
        this.clock=clock;
        this.endpoint=endpoint==null?"":endpoint.strip();
        this.baseModelReference=baseModelReference==null?"":baseModelReference.strip();
        this.remoteNodeEnabled=remoteNodeEnabled;
        this.remoteNodeToken=remoteNodeToken==null?"":remoteNodeToken.strip();
    }

    @Override
    public void run(ApplicationArguments arguments) {
        boolean configuredRemoteNode=remoteNodeEnabled && remoteNodeToken.length()>=32;
        if ((validEndpoint(endpoint) || configuredRemoteNode) && !baseModelReference.isBlank()) {
            repository.configureExternalLlm(baseModelReference,clock.instant());
        }
    }

    private static boolean validEndpoint(String value) {
        try {
            URI uri=URI.create(value);
            return ("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost()!=null && uri.getUserInfo()==null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
