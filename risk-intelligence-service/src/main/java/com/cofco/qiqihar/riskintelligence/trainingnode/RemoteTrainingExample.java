package com.cofco.qiqihar.riskintelligence.trainingnode;

import java.time.Instant;

public record RemoteTrainingExample(Instant resolvedAt,String input,boolean positive) { }
