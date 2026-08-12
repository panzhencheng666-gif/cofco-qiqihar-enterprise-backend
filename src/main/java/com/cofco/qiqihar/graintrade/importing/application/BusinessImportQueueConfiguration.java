package com.cofco.qiqihar.graintrade.importing.application;

import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "qiqihar.import.queue-enabled", matchIfMissing = true)
class BusinessImportQueueConfiguration {}
