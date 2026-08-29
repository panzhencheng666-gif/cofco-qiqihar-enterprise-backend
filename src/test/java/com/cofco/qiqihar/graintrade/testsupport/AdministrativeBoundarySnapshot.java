package com.cofco.qiqihar.graintrade.testsupport;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Restores an authoritative boundary and its render row after a test fixture replaces it. */
public final class AdministrativeBoundarySnapshot {
    private final String regionCode;
    private final Optional<Boundary> boundary;
    private final Optional<Render> render;

    private AdministrativeBoundarySnapshot(
            String regionCode, Optional<Boundary> boundary, Optional<Render> render) {
        this.regionCode = regionCode;
        this.boundary = boundary;
        this.render = render;
    }

    public static AdministrativeBoundarySnapshot capture(JdbcClient jdbc, String regionCode) {
        Optional<Boundary> boundary = jdbc.sql("""
                SELECT region_code,ST_AsEWKB(geometry) geometry,source_name,source_url,
                       source_revision,source_license,source_feature_id,source_effective_on,
                       geometry_sha256,loaded_at
                FROM overview.administrative_boundary WHERE region_code=:regionCode
                """).param("regionCode", regionCode).query((row, ignored) -> new Boundary(
                        row.getString("region_code"), row.getBytes("geometry"),
                        row.getString("source_name"), row.getString("source_url"),
                        row.getString("source_revision"), row.getString("source_license"),
                        row.getString("source_feature_id"),
                        row.getObject("source_effective_on", LocalDate.class),
                        row.getString("geometry_sha256"),
                        row.getObject("loaded_at", OffsetDateTime.class))).optional();
        Optional<Render> render = jdbc.sql("""
                SELECT region_code,ST_AsEWKB(geometry) geometry,geo_json,simplify_tolerance,
                       full_point_count,render_point_count,source_geometry_sha256,refreshed_at,
                       source_name,source_revision,source_license
                FROM overview.administrative_boundary_render WHERE region_code=:regionCode
                """).param("regionCode", regionCode).query((row, ignored) -> new Render(
                        row.getString("region_code"), row.getBytes("geometry"), row.getString("geo_json"),
                        row.getDouble("simplify_tolerance"), row.getInt("full_point_count"),
                        row.getInt("render_point_count"), row.getString("source_geometry_sha256"),
                        row.getObject("refreshed_at", OffsetDateTime.class), row.getString("source_name"),
                        row.getString("source_revision"), row.getString("source_license"))).optional();
        return new AdministrativeBoundarySnapshot(regionCode, boundary, render);
    }

    public void restore(JdbcClient jdbc) {
        jdbc.sql("DELETE FROM overview.administrative_boundary WHERE region_code=:regionCode")
                .param("regionCode", regionCode).update();
        boundary.ifPresent(value -> jdbc.sql("""
                INSERT INTO overview.administrative_boundary(
                  region_code,geometry,source_name,source_url,source_revision,source_license,
                  source_feature_id,source_effective_on,geometry_sha256,loaded_at)
                VALUES(:regionCode,ST_GeomFromEWKB(:geometry),:sourceName,:sourceUrl,:sourceRevision,
                  :sourceLicense,:sourceFeatureId,:sourceEffectiveOn,:geometrySha256,:loadedAt)
                """).param("regionCode", value.regionCode()).param("geometry", value.geometry())
                .param("sourceName", value.sourceName()).param("sourceUrl", value.sourceUrl())
                .param("sourceRevision", value.sourceRevision()).param("sourceLicense", value.sourceLicense())
                .param("sourceFeatureId", value.sourceFeatureId())
                .param("sourceEffectiveOn", value.sourceEffectiveOn())
                .param("geometrySha256", value.geometrySha256()).param("loadedAt", value.loadedAt()).update());
        if (boundary.isPresent()) {
            render.ifPresent(value -> jdbc.sql("""
                    INSERT INTO overview.administrative_boundary_render(
                      region_code,geometry,geo_json,simplify_tolerance,full_point_count,render_point_count,
                      source_geometry_sha256,refreshed_at,source_name,source_revision,source_license)
                    VALUES(:regionCode,ST_GeomFromEWKB(:geometry),:geoJson,:simplifyTolerance,
                      :fullPointCount,:renderPointCount,:sourceGeometrySha256,:refreshedAt,
                      :sourceName,:sourceRevision,:sourceLicense)
                    """).param("regionCode", value.regionCode()).param("geometry", value.geometry())
                    .param("geoJson", value.geoJson()).param("simplifyTolerance", value.simplifyTolerance())
                    .param("fullPointCount", value.fullPointCount())
                    .param("renderPointCount", value.renderPointCount())
                    .param("sourceGeometrySha256", value.sourceGeometrySha256())
                    .param("refreshedAt", value.refreshedAt()).param("sourceName", value.sourceName())
                    .param("sourceRevision", value.sourceRevision())
                    .param("sourceLicense", value.sourceLicense()).update());
        }
    }

    private record Boundary(
            String regionCode, byte[] geometry, String sourceName, String sourceUrl,
            String sourceRevision, String sourceLicense, String sourceFeatureId,
            LocalDate sourceEffectiveOn, String geometrySha256, OffsetDateTime loadedAt) {}

    private record Render(
            String regionCode, byte[] geometry, String geoJson, double simplifyTolerance,
            int fullPointCount, int renderPointCount, String sourceGeometrySha256,
            OffsetDateTime refreshedAt, String sourceName, String sourceRevision, String sourceLicense) {}
}
