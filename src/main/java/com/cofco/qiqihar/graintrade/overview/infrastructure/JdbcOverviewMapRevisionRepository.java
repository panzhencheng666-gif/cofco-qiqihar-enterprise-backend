package com.cofco.qiqihar.graintrade.overview.infrastructure;

import com.cofco.qiqihar.graintrade.overview.application.OverviewMapRevisionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOverviewMapRevisionRepository implements OverviewMapRevisionRepository {
    private final JdbcClient jdbc;
    public JdbcOverviewMapRevisionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    @Override
    public String currentRevision() {
        return jdbc.sql("SELECT revision::text FROM overview.map_revision WHERE singleton")
                .query(String.class).single();
    }
}
