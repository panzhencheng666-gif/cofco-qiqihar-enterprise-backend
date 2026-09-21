package com.cofco.qiqihar.graintrade.overview.application;

import java.util.List;

public interface PublicEventFeed {
    List<OperationalSituationRepository.FeedEvent> fetch() throws Exception;
}
