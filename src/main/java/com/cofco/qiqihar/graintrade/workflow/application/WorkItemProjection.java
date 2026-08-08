package com.cofco.qiqihar.graintrade.workflow.application;

/** Refreshes database-backed workflow projections before a work list is read. */
public interface WorkItemProjection {
    void refresh();
}
