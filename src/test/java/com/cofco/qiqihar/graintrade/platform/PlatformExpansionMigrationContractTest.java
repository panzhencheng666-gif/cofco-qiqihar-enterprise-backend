package com.cofco.qiqihar.graintrade.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PlatformExpansionMigrationContractTest {
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V209__add_design_sample_map_email_and_messaging.sql");

    @Test
    void addsOnlyCurrentDatabaseStructuresForTheApprovedCapabilities() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("ADD COLUMN lifecycle_status")
                .contains("CREATE TABLE platform.user_map_annotation")
                .contains("subject_id varchar(120) PRIMARY KEY")
                .contains("CREATE TABLE platform.email_identity")
                .contains("CREATE TABLE platform.email_challenge")
                .contains("CREATE TABLE platform.private_message")
                .contains("CREATE TABLE platform.private_message_receipt")
                .contains("CREATE TABLE platform.message_email_delivery")
                .contains("expires_at timestamptz NOT NULL")
                .contains("consumed_at timestamptz")
                .doesNotContain("CREATE DATABASE", "CREATE SERVER", "dblink", "postgres_fdw");
    }

    @Test
    void enforcesOwnershipGeometryAndPermanentMessageRetention() throws IOException {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("geometry geometry(Geometry,4326)")
                .contains("GeometryType(geometry) IN ('POINT','POLYGON')")
                .contains("recipient_subject_id varchar(120) NOT NULL")
                .contains("channel varchar(20) NOT NULL CHECK (channel IN ('STATION','EMAIL'))")
                .contains("CREATE FUNCTION platform.reject_private_message_delete()")
                .contains("BEFORE DELETE ON platform.private_message")
                .contains("REVOKE ALL ON TABLE platform.user_map_annotation FROM PUBLIC")
                .contains("REVOKE ALL ON TABLE platform.private_message FROM PUBLIC");
    }
}
