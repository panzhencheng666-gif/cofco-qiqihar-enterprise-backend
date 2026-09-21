package com.cofco.qiqihar.graintrade.messaging.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MessageEmailDeliveryWorker {
    private final MessageEmailDeliveryStore store;private final OutboundEmailGateway gateway;private final boolean enabled;
    public MessageEmailDeliveryWorker(MessageEmailDeliveryStore store,OutboundEmailGateway gateway,
            @Value("${QIQIHAR_EMAIL_ENABLED:false}") boolean enabled){this.store=store;this.gateway=gateway;this.enabled=enabled;}
    @Scheduled(fixedDelayString="${qiqihar.message.email.poll-delay:5s}")
    public void scheduled(){if(enabled)deliverNext();}
    public boolean deliverNext() {
        var item=store.claim();
        if(item.isEmpty())return false;
        var value=item.get();
        try {
            gateway.send(value.address(),value.title(),value.body());
            store.markSent(value.id());
        } catch(RuntimeException failure) {
            store.markFailed(value.id());
        }
        return true;
    }
}
