package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation.Mode.LINK;
import static org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation.Mode.UNLINK;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordCollectionSelection.Direction.FROM_SOURCE;
import static org.simplepoint.plugin.dna.runtime.service.record.RecordCollectionSelection.Direction.FROM_TARGET;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSort;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSortDirection;

/** Native query/SQL admission proof; not APP resource lifecycle or online business acceptance. */
class RecordCollectionPagingDatabaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void pagesAndCountsBothDirectionsAfterBothEndpointPolicies(boolean postgres) throws Exception {
    try (var fixture = new RecordReferenceIntegrityDatabaseTest.Fixture(postgres)) {
      var binding = RecordCollectionSqlDatabaseTest.install(fixture);
      var source = fixture.create(binding.source(), Map.of());
      var secondSource = fixture.create(binding.source(), Map.of());
      var statements = new ArrayList<RecordSqlStatement>(List.of(source, secondSource));
      var targetIds = new ArrayList<String>();
      for (int index = 0; index < 125; index++) {
        var target = fixture.create(binding.target(), Map.of());
        targetIds.add(target.recordId());
        statements.add(target);
        statements.add(link(fixture, binding, source.recordId(), target.recordId()));
      }
      statements.add(link(fixture, binding, secondSource.recordId(), targetIds.getFirst()));
      fixture.backend.executeOwned(
          binding.source(),
          List.of(binding.target(), binding.edge()),
          new RecordWriteAdmission("tenant-a", 0),
          statements,
          120_000,
          null);
      for (int offset = 0; offset < targetIds.size(); offset += 100) {
        var keys =
            targetIds.subList(offset, Math.min(offset + 100, targetIds.size())).stream()
                .map(id -> RecordCollectionEdgeKey.of(binding, "tenant-a", source.recordId(), id))
                .toList();
        var result =
            fixture.backend.query(
                binding.edge(), RecordCollectionSql.lookup(binding, keys), 30_000);
        assertThat(RecordCollectionEdgeReads.read(binding, keys, result).values())
            .hasSize(keys.size())
            .allMatch(state -> state.active() && state.version() == 0);
      }
      var forward =
          new RecordCollectionSelection(
              binding, FROM_SOURCE, source.recordId(), fixture.access(binding.source()));
      var fetched = new ArrayList<String>();
      for (int page = 0; page < 3; page++) {
        var result = read(fixture, forward, fixture.access(binding.target()), page, null);
        assertThat(result.total()).isEqualTo(125);
        assertThat(result.ids()).hasSize(page < 2 ? 50 : 25);
        fetched.addAll(result.ids());
      }
      assertThat(fetched).containsExactlyElementsOf(targetIds.stream().sorted().toList());
      var reverse =
          new RecordCollectionSelection(
              binding, FROM_TARGET, targetIds.getFirst(), fixture.access(binding.target()));
      assertThat(read(fixture, reverse, fixture.access(binding.source()), 0, null).ids())
          .containsExactlyInAnyOrder(source.recordId(), secondSource.recordId());
      var filter =
          new RecordFilter(
              RecordFilterOperator.EQUALS, "id", List.of(targetIds.getLast()), List.of());
      var filtered = read(fixture, forward, fixture.access(binding.target()), 0, filter);
      assertThat(filtered.total()).isEqualTo(1);
      assertThat(filtered.ids()).containsExactly(targetIds.getLast());
      var hiddenSource =
          new RecordCollectionSelection(
              binding,
              FROM_SOURCE,
              source.recordId(),
              RecordCollectionSqlDatabaseTest.restricted(fixture.access(binding.source())));
      assertThat(read(fixture, hiddenSource, fixture.access(binding.target()), 0, null))
          .isEqualTo(new Page(0, List.of()));
      assertThat(
              read(
                  fixture,
                  forward,
                  RecordCollectionSqlDatabaseTest.restricted(fixture.access(binding.target())),
                  0,
                  null))
          .isEqualTo(new Page(0, List.of()));
      var key =
          RecordCollectionEdgeKey.of(binding, "tenant-a", source.recordId(), targetIds.getFirst());
      RecordCollectionSqlDatabaseTest.execute(
          fixture, binding, key, 0, 0, new RecordCollectionEdgeState(0, true), UNLINK);
      assertThat(read(fixture, forward, fixture.access(binding.target()), 0, null).total())
          .isEqualTo(124);
      assertThat(read(fixture, reverse, fixture.access(binding.source()), 0, null))
          .isEqualTo(new Page(1, List.of(secondSource.recordId())));
      var retired =
          fixture.backend.query(binding.edge(), RecordCollectionSql.lookup(binding, key), 30_000);
      assertThat(RecordCollectionEdgeReads.read(binding, List.of(key), retired).get(key.id()))
          .isEqualTo(new RecordCollectionEdgeState(1, false));
      assertThat(fixture.count(binding.edge())).isEqualTo(126);
    }
  }

  private static RecordSqlStatement link(
      RecordReferenceIntegrityDatabaseTest.Fixture fixture,
      RecordCollectionBinding binding,
      String source,
      String target) {
    return RecordCollectionSql.change(
        binding,
        RecordCollectionEdgeKey.of(binding, "tenant-a", source, target),
        RecordCollectionEdgeState.absent(),
        new CollectionEdgeMutation(target, 0L, -1L, LINK),
        fixture.access(binding.source()),
        Instant.now());
  }

  private static Page read(
      RecordReferenceIntegrityDatabaseTest.Fixture fixture,
      RecordCollectionSelection selection,
      RecordAccessContext access,
      int page,
      RecordFilter filter) {
    var binding = selection.binding();
    var model = selection.direction() == FROM_SOURCE ? binding.target() : binding.source();
    var other = selection.direction() == FROM_SOURCE ? binding.source() : binding.target();
    var request =
        new RecordPageQuery(
            model.physicalModelId(),
            new RecordSchemaReference(1, "a".repeat(64)),
            page,
            50,
            List.of("id"),
            filter,
            List.of(new RecordSort("id", RecordSortDirection.ASC)));
    var plan = fixture.compiler.collectionPage(model, access, request, selection);
    var count = fixture.compiler.collectionCount(model, access, request, selection);
    return fixture.backend.read(
        model,
        List.of(other, binding.edge()),
        access,
        30_000,
        session -> {
          var rows = session.query().apply(plan);
          var total = session.query().apply(count);
          for (var result : List.of(rows, total)) {
            assertThat(result.truncated()).isFalse();
            assertThat(result.analysis().platformJoin()).as(result.analysis().planText()).isFalse();
            assertThat(result.analysis().pushedSqls()).hasSize(1);
          }
          return new Page(
              RecordQueryServiceImpl.countValue(total),
              RecordQueryServiceImpl.views(rows, plan, model, access).stream()
                  .map(row -> row.id())
                  .toList());
        });
  }

  private record Page(long total, List<String> ids) {}
}
