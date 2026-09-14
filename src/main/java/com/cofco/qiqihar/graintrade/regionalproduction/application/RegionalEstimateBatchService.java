package com.cofco.qiqihar.graintrade.regionalproduction.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class RegionalEstimateBatchService {
    private static final Logger LOG = LoggerFactory.getLogger(RegionalEstimateBatchService.class);
    private final RegionalPublicDataRepository publicData;
    private final RegionalEstimateBatchRepository batches;
    public RegionalEstimateBatchService(RegionalPublicDataRepository publicData, RegionalEstimateBatchRepository batches) {
        this.publicData = publicData; this.batches = batches;
    }
    public RegionalEstimateBatch load(String root, int year) {
        return batches.latest(root, year).orElseGet(() -> {
            var context = publicData.load(root, year);
            return new RegionalEstimateBatch(root, year, Instant.now().toString(), Instant.now().toString(), context.refreshStatus().lastAttemptAt(),
                    "ON_DEMAND", context.refreshStatus().status(), RegionalCurrentEstimates.VERSION,
                    RegionalCurrentEstimates.compare(publicData.history(root,year),year));
        });
    }
    public void refresh(Instant now) {
        int year = now.atZone(ZoneId.of("Asia/Shanghai")).getYear();
        for (String root : List.of("230200", "231100", "150700", "232700")) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                var history = publicData.history(root, year);
                var context = publicData.load(root, year);
                var comparisons = RegionalCurrentEstimates.compare(history, year);
                // Verification timestamps do not change the numerical input fingerprint; source and model changes do.
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                        (RegionalCurrentEstimates.VERSION + comparisons.toString()).getBytes(StandardCharsets.UTF_8)));
                String state = batches.latestHash(root,year).filter(hash::equals).isPresent() ? "RECALCULATED_UNCHANGED" : "RECALCULATED_CHANGED";
                batches.save(new RegionalEstimateBatch(root,year,now.toString(),now.toString(),context.refreshStatus().lastAttemptAt(),
                        state,context.refreshStatus().status(),RegionalCurrentEstimates.VERSION,comparisons),hash);
            } catch (Exception failure) {
                LOG.error("Regional estimate batch failed [root={}]", root, failure);
                try {
                    var previous = batches.latest(root,year).orElse(new RegionalEstimateBatch(root,year,null,now.toString(),null,
                            "FAILED_RETAINED","UNKNOWN",RegionalCurrentEstimates.VERSION,List.of()));
                    batches.save(new RegionalEstimateBatch(root,year,previous.calculatedAt(),now.toString(),previous.sourceCheckedAt(),
                            "FAILED_RETAINED",previous.sourceStatus(),previous.modelVersion(),previous.comparisons()),
                            batches.latestHash(root,year).orElse(""));
                } catch (Exception persistenceFailure) {
                    LOG.error("Regional estimate failure status could not be persisted [root={}]",root,persistenceFailure);
                }
            }
        }
    }
}
