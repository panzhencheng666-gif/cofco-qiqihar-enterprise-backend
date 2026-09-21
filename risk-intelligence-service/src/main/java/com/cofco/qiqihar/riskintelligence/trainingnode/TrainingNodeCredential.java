package com.cofco.qiqihar.riskintelligence.trainingnode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

final class TrainingNodeCredential {
    private static final Pattern NODE_ID=Pattern.compile("[A-Za-z0-9._-]{1,80}");

    private TrainingNodeCredential() { }

    static boolean authorized(String authorization,String configuredToken) {
        if (configuredToken==null || configuredToken.isBlank() || authorization==null
                || !authorization.startsWith("Bearer ")) return false;
        byte[] expected=configuredToken.getBytes(StandardCharsets.UTF_8);
        byte[] supplied=authorization.substring(7).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected,supplied);
    }

    static String requireNodeId(String nodeId) {
        if (nodeId==null || !NODE_ID.matcher(nodeId).matches()) {
            throw new IllegalArgumentException("训练节点标识不合法");
        }
        return nodeId;
    }
}
