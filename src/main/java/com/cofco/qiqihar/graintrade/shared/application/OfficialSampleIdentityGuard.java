package com.cofco.qiqihar.graintrade.shared.application;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Map;

/** Business fact edits cannot silently change the identity of an existing formal sample. */
public final class OfficialSampleIdentityGuard {
    private OfficialSampleIdentityGuard() {}

    public static void requireUnchanged(Map<String,String> before, Map<String,String> after, String prefix) {
        for (String suffix : new String[]{"SAMPLE_NAME", "SAMPLE_LONGITUDE", "SAMPLE_LATITUDE"}) {
            String field=prefix+suffix;
            String original=before.get(field), replacement=after.get(field);
            boolean same;
            if (suffix.endsWith("ITUDE")) {
                try { same=new BigDecimal(original).compareTo(new BigDecimal(replacement))==0; }
                catch (RuntimeException exception) { same=false; }
            } else {
                same=normalize(original).equals(normalize(replacement));
            }
            if (!same) throw ClientRequestException.field("FORMAL_SAMPLE_IDENTITY_LOCKED",field,
                    "已入库记录的样本身份和坐标不能在业务数据中变更，请在样本点维护中修正。");
        }
    }

    private static String normalize(String value) {
        return value==null ? "" : Normalizer.normalize(value,Normalizer.Form.NFKC).strip();
    }
}
