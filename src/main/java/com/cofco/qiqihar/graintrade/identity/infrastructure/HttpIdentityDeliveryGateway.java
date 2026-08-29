package com.cofco.qiqihar.graintrade.identity.infrastructure;

import com.cofco.qiqihar.graintrade.identity.application.IdentityDeliveryGateway;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** HTTPS adapter for the enterprise-owned provisioning and invitation sender. */
@Component
public final class HttpIdentityDeliveryGateway implements IdentityDeliveryGateway {
    private final HttpClient http=HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    private final String endpoint;
    private final String bearerToken;
    private final String activationUrl;

    public HttpIdentityDeliveryGateway(ObjectMapper json,
            @Value("${qiqihar.identity.delivery.endpoint:}") String endpoint,
            @Value("${qiqihar.identity.delivery.bearer-token:}") String bearerToken,
            @Value("${qiqihar.identity.delivery.activation-url:}") String activationUrl) {
        this.json=json;this.endpoint=endpoint;this.bearerToken=bearerToken;this.activationUrl=activationUrl;
    }

    @Override
    public void deliver(DeliveryCommand command) {
        URI target=https(endpoint,"delivery endpoint");
        https(activationUrl,"activation URL");
        if(bearerToken.isBlank())throw new IdentityDeliveryUnavailableException("delivery credential unavailable");
        try {
            String body=json.writeValueAsString(Map.of(
                    "contractVersion","2026-08-30",
                    "eventId",command.eventId().toString(),
                    "subjectId",command.subjectId(),
                    "deliveryAddress",command.deliveryAddress(),
                    "activationUrl",activationUrl+"#activate="+command.activationToken(),
                    "expiresAt",command.expiresAt().toString(),
                    "publicSelfRegistrationEnabled",false));
            HttpRequest request=HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(10))
                    .header("Authorization","Bearer "+bearerToken)
                    .header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<Void> response=http.send(request,HttpResponse.BodyHandlers.discarding());
            if(response.statusCode()<200||response.statusCode()>=300)
                throw new IdentityDeliveryUnavailableException("delivery adapter rejected request");
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IdentityDeliveryUnavailableException("delivery adapter interrupted",interrupted);
        } catch(java.io.IOException|tools.jackson.core.JacksonException failure) {
            throw new IdentityDeliveryUnavailableException("delivery adapter unavailable",failure);
        }
    }

    private static URI https(String value,String name) {
        try {
            URI uri=URI.create(value);
            if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null
                    ||uri.getUserInfo()!=null||uri.getFragment()!=null)
                throw new IllegalArgumentException();
            return uri;
        } catch(IllegalArgumentException invalid) {
            throw new IdentityDeliveryUnavailableException(name+" is not controlled HTTPS");
        }
    }

    static final class IdentityDeliveryUnavailableException extends RuntimeException {
        IdentityDeliveryUnavailableException(String message){super(message);}
        IdentityDeliveryUnavailableException(String message,Throwable cause){super(message,cause);}
    }
}
