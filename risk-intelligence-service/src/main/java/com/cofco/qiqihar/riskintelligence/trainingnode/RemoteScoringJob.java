package com.cofco.qiqihar.riskintelligence.trainingnode;

import java.time.Instant;
import java.util.UUID;

record RemoteScoringJob(UUID modelId,int modelVersion,String baseModelReference,
        String artifactReference,String artifactSha256,UUID assessmentId,
        String input,String lifecyclePhase,Instant leaseUntil) { }
