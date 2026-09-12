package com.cofco.qiqihar.graintrade.identity.application;

import java.util.Collection;
import java.util.HashSet;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;

final class AccountRegionPolicy {
    private AccountRegionPolicy() {}
    static void requireAtMostTen(String subject, Collection<String> regions) {
        requireAtMostTen(subject,regions,false);
    }
    static void requireAtMostTen(String subject, Collection<String> regions, boolean administrator) {
        if (!administrator && new HashSet<>(regions).size() > 10)
            throw new ClientRequestException("ACCOUNT_REGION_LIMIT", "每个账号最多绑定 10 个乡镇，请先撤销多余的授权或责任区域");
    }
}
