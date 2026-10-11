package org.simplepoint.plugin.workflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.query.InvalidQueryException;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CreateDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.DefinitionSummary;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

class WorkflowDefinitionQueriesTest extends WorkflowEngineFixture {
  @Test
  void headerFiltersAndSortAreAppliedBeforeDatabasePagination() {
    transaction.executeWithoutResult(
        status -> {
          for (int i = 0; i < 27; i++) {
            service.createDefinition(
                new CreateDefinitionCommand(
                    "process" + i,
                    String.format("Process %02d", i),
                    i % 2 == 0 ? "Target" : "Other",
                    "Description"));
          }
        });
    var rows =
        transaction.execute(
            status ->
                service.queryDefinitions(
                    Map.of("category", "equals:Target"), PageRequest.of(2, 5, Sort.by("name"))));
    assertThat(rows.totalElements()).isEqualTo(14);
    assertThat(rows.content())
        .extracting(DefinitionSummary::name)
        .containsExactly("Process 20", "Process 22", "Process 24", "Process 26");
  }

  @Test
  void legacyKeywordAndTypedColumnFiltersCombineWithoutExpandingTenantScope() {
    transaction.executeWithoutResult(
        status -> {
          service.createDefinition(
              new CreateDefinitionCommand("requestOne", "Budget 100%", "Finance", "First"));
          service.createDefinition(
              new CreateDefinitionCommand("requestTwo", "Budget 1000", "Other", "Second"));
        });
    var literal =
        transaction.execute(
            status -> service.queryDefinitions(Map.of("keyword", "%"), PageRequest.of(0, 20)));
    assertThat(literal.content())
        .extracting(DefinitionSummary::name)
        .containsExactly("Budget 100%");
    var combined =
        transaction.execute(
            status ->
                service.queryDefinitions(
                    Map.of(
                        "keyword",
                        "REQUEST",
                        "category",
                        "equals:finance",
                        "latestVersion",
                        "equals:0"),
                    PageRequest.of(0, 20)));
    assertThat(combined.totalElements()).isEqualTo(1);
    authorize("actor", "foreign", true, List.of());
    assertThat(
            transaction
                .execute(status -> service.queryDefinitions(Map.of(), PageRequest.of(0, 20)))
                .content())
        .isEmpty();
  }

  @Test
  void equalSortValuesHaveAStableUniqueTieBreakerAcrossPages() {
    transaction.executeWithoutResult(
        status -> {
          for (int i = 0; i < 12; i++) {
            service.createDefinition(
                new CreateDefinitionCommand("process" + i, "Same", null, null));
          }
          SharedEntityManagerCreator.createSharedEntityManager(factory)
              .createQuery("update WorkflowDefinitionEntity set updatedAt = :time")
              .setParameter("time", Instant.parse("2026-01-01T00:00:00Z"))
              .executeUpdate();
        });
    var first =
        transaction.execute(
            status ->
                service.queryDefinitions(Map.of(), PageRequest.of(0, 6, Sort.by("updatedAt"))));
    var next =
        transaction.execute(
            status ->
                service.queryDefinitions(Map.of(), PageRequest.of(1, 6, Sort.by("updatedAt"))));
    assertThat(first.content()).hasSize(6).doesNotContainAnyElementsOf(next.content());
    assertThat(next.content()).hasSize(6);
  }

  @Test
  void schemaMatchesTheAllowlistAndRejectsUnknownFieldsOversizedPagesAndMissingPermission() {
    assertThat(service.definitionsSchema().toString())
        .contains("x-query", "latestVersion", "date-time")
        .doesNotContain("draftXml", "createdBy", "tenantId");
    for (var filters :
        List.of(Map.of("tenantId", "equals:foreign"), Map.of("latestVersion", "equals:invalid"))) {
      assertThatThrownBy(
              () ->
                  transaction.execute(
                      status -> service.queryDefinitions(filters, PageRequest.of(0, 20))))
          .isInstanceOf(InvalidQueryException.class);
    }
    assertThatThrownBy(() -> service.queryDefinitions(Map.of(), PageRequest.of(0, 101)))
        .isInstanceOf(InvalidQueryException.class);
    assertThatThrownBy(
            () -> service.queryDefinitions(Map.of(), PageRequest.of(0, 20, Sort.by("draftXml"))))
        .isInstanceOf(InvalidQueryException.class);
    authorize("actor", "tenant", false, List.of());
    assertThatThrownBy(service::definitionsSchema).isInstanceOf(WorkflowException.class);
  }
}
