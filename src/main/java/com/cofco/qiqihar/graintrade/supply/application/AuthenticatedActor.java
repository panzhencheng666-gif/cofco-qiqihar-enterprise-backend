package com.cofco.qiqihar.graintrade.supply.application;
public record AuthenticatedActor(String id){public AuthenticatedActor{if(id==null||id.isBlank())throw new IllegalArgumentException("actor required");}}
