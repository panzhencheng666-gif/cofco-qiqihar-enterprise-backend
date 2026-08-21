package com.cofco.qiqihar.graintrade.production.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.when;

import com.cofco.qiqihar.graintrade.production.domain.ProductionRecord;
import com.cofco.qiqihar.graintrade.production.domain.ProductionRecordQuery;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.PageDefinitionQuery;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProductionRecordServiceTest {

    @Test
    void organizesRepositoryMasterDataIntoSortedLabeledGroupsWithoutDiscardingFutureCategories() {
        ProductionRecordRepository repository = mock(ProductionRecordRepository.class);
        when(repository.isApplicableObjectType("RICE", "FARMER")).thenReturn(true);
        when(repository.findFactCategories()).thenReturn(List.of(
                new ProductionFactCategory("EVIDENCE", "佐证材料", 50),
                new ProductionFactCategory("QUALITY", "质量指标", 10),
                new ProductionFactCategory("COST", "生产成本", 20)));
        when(repository.findFactDefinitions("RICE", "FARMER")).thenReturn(List.of(
                definition("PHOTO_COUNT", "EVIDENCE", "照片数量", 501),
                definition("IMPURITY", "QUALITY", "杂质", 102),
                definition("MOISTURE", "QUALITY", "水分", 101)));
        ProductionRecordService service = service(repository, mock(PageDefinitionQuery.class));

        ProductionFormDefinition result = service.factDefinition("RICE", "FARMER");

        assertThat(result.productCode()).isEqualTo("RICE");
        assertThat(result.objectTypeCode()).isEqualTo("FARMER");
        assertThat(result.groups()).extracting(ProductionFactGroup::category, ProductionFactGroup::label)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("QUALITY", "质量指标"),
                        org.assertj.core.groups.Tuple.tuple("COST", "生产成本"),
                        org.assertj.core.groups.Tuple.tuple("EVIDENCE", "佐证材料"));
        assertThat(result.groups().get(0).fields()).extracting(ProductionFactDefinition::code)
                .containsExactly("MOISTURE", "IMPURITY");
        assertThat(result.groups().get(1).fields()).isEmpty();
        assertThat(result.groups().get(2).fields()).extracting(ProductionFactDefinition::code)
                .containsExactly("PHOTO_COUNT");
        assertThat(result.contractVersion()).isEqualTo("production-survey-fields-v4");
        assertThat(result.fields()).extracting(ProductionSurveyField::code)
                .startsWith("objectTypeCode", "regionCode", "surveyYear",
                        "surveyMonth", "surveyDate", "PROD_SAMPLE_NAME")
                .containsSubsequence("cultivatedAreaMu", "yieldPerMuKilograms", "estimatedOutputKilograms")
                .containsSubsequence("MOISTURE", "IMPURITY", "PHOTO_COUNT")
                .doesNotContain("PROD_CULTIVAR_NAME", "PROD_SAMPLE_SUBJECT_CODE", "sample_point_id");
        ProductionSurveyField subjectName = result.fields().stream()
                .filter(field -> field.code().equals("PROD_SAMPLE_NAME"))
                .findFirst().orElseThrow();
        assertThat(subjectName.label()).isEqualTo("样本点名称");
        assertThat(subjectName.required()).isFalse();
        assertThat(subjectName.readOnly()).isFalse();
        assertThat(result.fields()).extracting(ProductionSurveyField::code)
                .doesNotHaveDuplicates();

        ProductionImportDefinition importDefinition = service.importDefinition("RICE", "FARMER");
        assertThat(importDefinition.contractVersion()).isEqualTo(result.contractVersion());
        assertThat(importDefinition.fields()).isEqualTo(result.fields());
    }

    @Test
    void rejectsDefinitionsWhoseCategoryIsAbsentFromTheMasterDataContract() {
        ProductionRecordRepository repository = mock(ProductionRecordRepository.class);
        when(repository.isApplicableObjectType("CORN", "FARMER")).thenReturn(true);
        when(repository.findFactCategories()).thenReturn(List.of(
                new ProductionFactCategory("QUALITY", "质量指标", 10)));
        when(repository.findFactDefinitions("CORN", "FARMER")).thenReturn(List.of(
                definition("FUTURE_FACT", "UNREGISTERED", "未来事实", 1)));
        ProductionRecordService service = service(repository, mock(PageDefinitionQuery.class));

        assertThatThrownBy(() -> service.factDefinition("CORN", "FARMER"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNREGISTERED");
    }

    @Test
    void computesListActionsInTheApplicationFromStatusAndConfiguredPageActions() {
        ProductionRecordRepository repository = mock(ProductionRecordRepository.class);
        PageDefinitionQuery pageDefinitions = mock(PageDefinitionQuery.class);
        ProductionRecordQuery query = new ProductionRecordQuery(
                "CORN", "MONITORING", 0, 20, Map.of());
        when(pageDefinitions.allowsListQueryValues("PRODUCTION", "MONITORING", "CORN", 20, Map.of()))
                .thenReturn(true);
        when(repository.findPage(query)).thenReturn(new PagedResult<>(List.of(
                new ProductionListRow("record-1", Map.of("PROD_STATUS", "草稿"),
                        com.cofco.qiqihar.graintrade.production.domain.ProductionStatus.DRAFT,
                        Set.of("VIEW", "SUBMIT", "RETURN"), 3)), 0, 20, 1));
        ProductionRecordService service = service(repository, pageDefinitions);

        PagedResult<ProductionListItem> result = service.read(query);

        assertThat(result.items().get(0).allowedActions()).containsExactly("VIEW", "SUBMIT");
    }

    @Test
    void computesDetailActionsInTheApplication() {
        ProductionRecordRepository repository = mock(ProductionRecordRepository.class);
        ProductionRecord record = record();
        when(repository.findById("record-1")).thenReturn(Optional.of(record));
        ProductionRecordService service = service(repository, mock(PageDefinitionQuery.class));

        ProductionRecordView result = service.detail("record-1");

        assertThat(result.record()).isSameAs(record);
        assertThat(result.allowedActions()).containsExactly("VIEW", "SAVE", "SUBMIT", "VOID");
    }

    @Test
    void doesNotMisclassifyAnInfrastructureRuntimeFailureAsClientValidation() {
        ProductionRecordRepository repository = mock(ProductionRecordRepository.class);
        when(repository.isKnownRegion("230202")).thenThrow(new RuntimeException("database unavailable"));
        CurrentActor actor = () -> Optional.of(new AuthenticatedActor("tester"));
        ProductionRecordService service = new ProductionRecordService(repository, mock(PageDefinitionQuery.class),
                actor, fixedClock());
        ProductionDraft draft = new ProductionDraft("SOYBEAN", "FARMER", "230202", null,
                LocalDate.of(2026, 8, 1), new BigDecimal("1"), new BigDecimal("2"), Map.of(), Map.of(), Map.of(),
                Map.of(), submissionMetadata());

        assertThatThrownBy(() -> service.create(draft))
                .isExactlyInstanceOf(RuntimeException.class)
                .hasMessage("database unavailable");
    }

    @Test
    void translatesOnlyTypedDomainValidationFailuresToAClientRequest() {
        ProductionRecordRepository repository = mock(ProductionRecordRepository.class);
        when(repository.isKnownRegion("230202")).thenReturn(true);
        when(repository.isApplicableObjectType("SOYBEAN", "FARMER")).thenReturn(true);
        when(repository.areApplicableFacts("SOYBEAN", "FARMER", Map.of(
                "QUALITY", java.util.Set.of(), "COST", java.util.Set.of(),
                "INSURANCE", java.util.Set.of(), "SUBSIDY", java.util.Set.of()))).thenReturn(true);
        CurrentActor actor = () -> Optional.of(new AuthenticatedActor("tester"));
        ProductionRecordService service = new ProductionRecordService(repository, mock(PageDefinitionQuery.class),
                actor, fixedClock());
        ProductionDraft draft = new ProductionDraft("SOYBEAN", "FARMER", "230202", null,
                LocalDate.of(2026, 8, 1), new BigDecimal("-1"), new BigDecimal("2"), Map.of(), Map.of(), Map.of(),
                Map.of(), submissionMetadata());

        assertThatThrownBy(() -> service.create(draft))
                .isInstanceOf(ClientRequestException.class)
                .extracting("code")
                .isEqualTo("INVALID_PRODUCTION_RECORD");
    }

    @Test
    void createCarriesRequiredSubmissionMetadataIntoThePersistedRecord() {
        ProductionRecordRepository repository = mock(ProductionRecordRepository.class);
        when(repository.isKnownRegion("230202")).thenReturn(true);
        when(repository.isApplicableObjectType("SOYBEAN", "FARMER")).thenReturn(true);
        when(repository.areApplicableFacts(eq("SOYBEAN"), eq("FARMER"), any())).thenReturn(true);
        when(repository.insert(any(), eq("tester"))).thenAnswer(invocation -> invocation.getArgument(0));
        ProductionRecordService service = service(repository, mock(PageDefinitionQuery.class));
        ProductionDraft draft = new ProductionDraft("SOYBEAN", "FARMER", "230202", null,
                LocalDate.of(2026, 8, 1), new BigDecimal("1"), new BigDecimal("2"), Map.of(), Map.of(), Map.of(),
                Map.of(), submissionMetadata());

        ProductionRecordView created = service.create(draft);

        Map<String, String> expected = new java.util.LinkedHashMap<>(submissionMetadata());
        expected.put("PROD_REPORTER_NAME", "tester");
        assertThat(created.record().submissionMetadata()).containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    void saveDraftReplacesThePersistedSubmissionMetadata() {
        ProductionRecordRepository repository = mock(ProductionRecordRepository.class);
        ProductionRecord existing = ProductionRecord.draft(
                "record-1", "SOYBEAN", "FARMER", "230202", null,
                LocalDate.of(2026, 8, 1), OffsetDateTime.parse("2026-08-02T08:00:00+08:00"),
                new BigDecimal("1"), new BigDecimal("2"), Map.of(), Map.of(), Map.of(), Map.of(),
                submissionMetadata());
        Map<String, String> replacement = new java.util.LinkedHashMap<>(submissionMetadata());
        replacement.put("PROD_REPORTER_NAME", "修改填报员");
        when(repository.findById("record-1")).thenReturn(Optional.of(existing));
        when(repository.isKnownRegion("230202")).thenReturn(true);
        when(repository.isApplicableObjectType("SOYBEAN", "FARMER")).thenReturn(true);
        when(repository.areApplicableFacts(eq("SOYBEAN"), eq("FARMER"), any())).thenReturn(true);
        when(repository.updateFacts(any(), eq(0L), eq("tester"))).thenAnswer(invocation ->
                ((ProductionRecord) invocation.getArgument(0)).savedAsVersion(1));
        ProductionRecordService service = service(repository, mock(PageDefinitionQuery.class));
        ProductionDraft draft = new ProductionDraft("SOYBEAN", "FARMER", "230202", null,
                LocalDate.of(2026, 8, 1), new BigDecimal("1"), new BigDecimal("2"), Map.of(), Map.of(), Map.of(),
                Map.of(), replacement);

        ProductionRecordView saved = service.saveDraft("record-1", 0, draft);

        assertThat(saved.record().version()).isOne();
        assertThat(saved.record().submissionMetadata()).containsExactlyInAnyOrderEntriesOf(submissionMetadata());
    }

    private static ProductionRecordService service(ProductionRecordRepository repository,
            PageDefinitionQuery pageDefinitions) {
        CurrentActor actor = () -> Optional.of(new AuthenticatedActor("tester"));
        return new ProductionRecordService(repository, pageDefinitions, actor, fixedClock());
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-08-02T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
    }

    private static ProductionFactDefinition definition(String code, String category, String label, int sortOrder) {
        return new ProductionFactDefinition(code, category, label, "DECIMAL", "%", "", 18, 1, sortOrder);
    }

    private static Map<String, String> submissionMetadata() {
        return Map.of(
                "PROD_REPORTER_NAME", "测试填报员",
                "PROD_SURVEYOR_PHONE", "13800000000",
                "PROD_SAMPLE_CONTACT", "13900000000",
                "PROD_SAMPLE_LATITUDE", "47.3543",
                "PROD_SAMPLE_LONGITUDE", "123.9182");
    }

    private static com.cofco.qiqihar.graintrade.production.domain.ProductionRecord record() {
        return com.cofco.qiqihar.graintrade.production.domain.ProductionRecord.draft(
                "record-1", "CORN", "FARMER", "230202", null,
                LocalDate.of(2026, 8, 1), OffsetDateTime.parse("2026-08-02T08:00:00+08:00"),
                new BigDecimal("10"), new BigDecimal("20"), Map.of(), Map.of(), Map.of(), Map.of());
    }
}
