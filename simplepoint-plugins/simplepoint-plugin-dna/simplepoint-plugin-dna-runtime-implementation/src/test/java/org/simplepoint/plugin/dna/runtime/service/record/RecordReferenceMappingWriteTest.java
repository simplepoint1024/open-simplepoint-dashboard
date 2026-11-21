package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.MappedOwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceConversion;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMapping;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingConfirmation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingObservation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceReadRequirement;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationField;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceField;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;

class RecordReferenceMappingWriteTest {
  private static final String ROW = "11111111-1111-1111-1111-111111111111";
  private static final String TARGET = "22222222-2222-2222-2222-222222222222";
  private static final Function<RecordSelectPlan, CalciteQueryResult> EMPTY =
      query -> new CalciteQueryResult(List.of(), List.of(), false, 0, 0, null);

  @Test
  void confirmedCopyEntersCalculationBeforeSqlAndCarriesTheSameFrozenProof() {
    var f = fixture(RecordReferenceMapping.Mode.SERVER_SNAPSHOT);
    var command = f.command(Map.of("customer_id", TARGET), f.observe(), List.of());
    var loaded = f.load(command);
    var prepared =
        f.calculations()
            .prepareMapped(
                f.binding(),
                f.scope(),
                Map.of(),
                command.command(),
                ROW,
                f.operation(command),
                EMPTY,
                Instant.parse("2026-10-29T00:00:00Z"),
                loaded);
    assertThat(prepared.statements()).hasSize(1);
    assertThat(prepared.statements().getFirst().parameters()).contains("hello😀", "HELLO😀");
    assertThat(prepared.references())
        .singleElement()
        .satisfies(
            check -> {
              assertThat(check.expectedTargetVersion()).isEqualTo(7);
              assertThat(check.mappingRevision()).isEqualTo(f.observe().revision());
            });
    assertThat(prepared.copies())
        .singleElement()
        .satisfies(
            copy -> {
              assertThat(copy.origins())
                  .singleElement()
                  .satisfies(
                      origin -> assertThat(origin.requirements()).containsExactly(f.requirement()));
              assertThat(copy.derivedOrigins())
                  .singleElement()
                  .satisfies(
                      origin -> {
                        assertThat(origin.destinationFieldId()).isEqualTo("upper");
                        assertThat(origin.requirements()).containsExactly(f.requirement());
                      });
            });
    var preview =
        f.calculations()
            .previewMapped(
                f.binding(),
                f.scope(),
                Map.of(),
                command.command(),
                ROW,
                f.operation(command),
                EMPTY,
                Instant.parse("2026-10-29T00:00:00Z"),
                new RecordCalculationAccess.Rules(Map.of(), Map.of()),
                f.plan().policy(),
                loaded);
    assertThat(preview.values().parent().values())
        .containsEntry("address", "hello😀")
        .containsEntry("upper", "HELLO😀");
    assertThat(command.command().parent().mutations().getFirst())
        .isEqualTo(new RecordCreateMutation("create", Map.of("customer_id", TARGET)));
  }

  @Test
  void editableDefaultCannotSilentlyOverwriteAnExplicitDifferentValue() {
    var f = fixture(RecordReferenceMapping.Mode.EDITABLE_DEFAULT);
    var command =
        f.command(Map.of("customer_id", TARGET, "address", "manual"), f.observe(), List.of("copy"));
    var loaded = f.load(command);
    assertThatThrownBy(
            () ->
                f.calculations()
                    .prepareMapped(
                        f.binding(),
                        f.scope(),
                        Map.of(),
                        command.command(),
                        ROW,
                        f.operation(command),
                        EMPTY,
                        Instant.now(),
                        loaded))
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            error ->
                assertThat(error.errorCode())
                    .isEqualTo(DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT));
    var same =
        f.command(
            Map.of("customer_id", TARGET, "address", "hello😀"), f.observe(), List.of("copy"));
    assertThat(
            f.calculations()
                .prepareMapped(
                    f.binding(),
                    f.scope(),
                    Map.of(),
                    same.command(),
                    ROW,
                    f.operation(same),
                    EMPTY,
                    Instant.now(),
                    f.load(same))
                .copies())
        .hasSize(1);
  }

  @Test
  void decliningDefaultsReadsOnlyTargetIdentityAndRetainsTheUserValue() {
    var f = fixture(RecordReferenceMapping.Mode.EDITABLE_DEFAULT);
    var command =
        f.command(Map.of("customer_id", TARGET, "address", "manual"), f.observe(), List.of());
    var reads = new AtomicInteger();
    var loaded =
        RecordReferenceMappingWrite.load(
            f.plan(),
            command,
            Map.of("source", f.scope()),
            (target, ids, fields) -> {
              reads.incrementAndGet();
              assertThat(fields).isEmpty();
              return Map.of(
                  TARGET,
                  new RecordReferenceMappingTargets.Captured(
                      new RecordView(TARGET, 7, Map.of()), Map.of()));
            });
    var result =
        f.calculations()
            .prepareMapped(
                f.binding(),
                f.scope(),
                Map.of(),
                command.command(),
                ROW,
                f.operation(command),
                EMPTY,
                Instant.now(),
                loaded);
    assertThat(reads.get()).isEqualTo(1);
    assertThat(result.statements().getFirst().parameters()).contains("manual", "MANUAL");
    assertThat(result.copies()).isEmpty();
  }

  @Test
  void clearingUsesOnlySourceAuthorityAndProducesNoInventedTargetProof() {
    var f = fixture(RecordReferenceMapping.Mode.SERVER_SNAPSHOT);
    var original = f.plan().bindings().get("customer");
    var noTarget =
        new RecordReferenceAdmission.Plan(
            f.plan().policy(),
            f.plan().sources(),
            Map.of(
                "customer", new RecordReferenceAdmission.Binding(original.rule(), "source", null)));
    var clear = RecordReferenceMappingClear.bind(noTarget, "customer", "tenant").observe();
    var command = f.command(Map.of(), clear, List.of());
    var loaded =
        RecordReferenceMappingWrite.load(
            noTarget,
            command,
            Map.of("source", f.scope()),
            (target, ids, fields) -> {
              throw new AssertionError("Unlink must not read the target");
            });
    var applied =
        loaded.apply(
            f.binding(),
            List.of(
                new RecordReferenceMappingWrite.Input(
                    f.scope().model(), ROW, null, null, Map.of(), Map.of())),
            EMPTY);
    assertThat(applied.patches().get(List.of("source", ROW))).containsEntry("address", null);
    assertThat(applied.effects().get(List.of("source", ROW)).origins()).isEmpty();
  }

  @Test
  void staleObservationOrMissingRowCannotBecomeAnOrdinaryWrite() {
    var f = fixture(RecordReferenceMapping.Mode.SERVER_SNAPSHOT);
    var command =
        f.command(
            Map.of("customer_id", TARGET),
            new RecordReferenceMappingObservation(f.observe().revision(), TARGET, 6L),
            List.of());
    assertThatThrownBy(() -> f.load(command)).isInstanceOf(DnaRuntimeException.class);
    var valid = f.command(Map.of("customer_id", TARGET), f.observe(), List.of());
    assertThatThrownBy(() -> f.load(valid).apply(f.binding(), List.of(), EMPTY))
        .isInstanceOf(DnaRuntimeException.class);
    var changed = f.command(Map.of(), f.observe(), List.of());
    assertThatThrownBy(
            () ->
                f.calculations()
                    .prepareMapped(
                        f.binding(),
                        f.scope(),
                        Map.of(),
                        changed.command(),
                        ROW,
                        f.operation(changed),
                        EMPTY,
                        Instant.now(),
                        f.load(changed)))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () ->
                f.calculations()
                    .prepare(
                        f.binding(),
                        f.scope(),
                        Map.of(),
                        valid.command(),
                        ROW,
                        f.operation(valid),
                        EMPTY,
                        Instant.now()))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void sourcePolicyDriftIsRejectedBeforeParticipantLocksOrSql() throws Exception {
    var f = fixture(RecordReferenceMapping.Mode.SERVER_SNAPSHOT);
    var command = f.command(Map.of("customer_id", TARGET), f.observe(), List.of());
    var prepared =
        f.calculations()
            .prepareMapped(
                f.binding(),
                f.scope(),
                Map.of(),
                command.command(),
                ROW,
                f.operation(command),
                EMPTY,
                Instant.now(),
                f.load(command));
    var changed =
        new RecordReferenceAdmission.Plan(
            new RecordCalculationStore.Installed(2, f.plan().policy().policy()),
            f.plan().sources(),
            f.plan().bindings());
    var connection = mock(java.sql.Connection.class);
    when(connection.getTransactionIsolation())
        .thenReturn(java.sql.Connection.TRANSACTION_READ_COMMITTED);
    var admission =
        new RecordReferenceAdmissionSession(
            changed,
            connection,
            null,
            "tenant",
            new RecordExecutionDeadline(30000),
            new RecordSqlCompiler(new StandardLogicalValueConverter()),
            query -> {
              throw new AssertionError("No SQL before revision validation");
            });
    assertThatThrownBy(
            () ->
                admission.validate(
                    new RecordWritePreparation(
                        prepared.statements(),
                        null,
                        1,
                        prepared.references(),
                        null,
                        prepared.copies())))
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT));
    org.mockito.Mockito.verify(connection).getAutoCommit();
    org.mockito.Mockito.verify(connection).getTransactionIsolation();
    org.mockito.Mockito.verifyNoMoreInteractions(connection);
  }

  @Test
  void detailConfirmationsApplyToTheirFinalPositionBeforeCalculationAndCannotMoveToAnotherRow() {
    var original = fixture(RecordReferenceMapping.Mode.SERVER_SNAPSHOT);
    var codec = new PhysicalSchemaCodec(new ObjectMapper());
    var originalSource = original.scope().model();
    var rootSchema =
        new PhysicalSchemaDefinition(DnaRuntimeApiVersion.CURRENT, List.of(), List.of());
    var root =
        new RuntimeRecordModel(
            "source", "app", "source-form", 1, rootSchema, originalSource.target());
    var fields = new java.util.ArrayList<>(originalSource.activeSchema().fields());
    fields.add(
        new LogicalFieldDefinition(
            "owner",
            "parent_record_id",
            "Owner",
            LogicalFieldType.UUID,
            false,
            null,
            null,
            null,
            null));
    var childSchema =
        new PhysicalSchemaDefinition(
            DnaRuntimeApiVersion.CURRENT,
            fields,
            List.of(),
            new org.simplepoint.plugin.dna.runtime.api.schema.OwnedModelRelation("source", "owner"),
            true,
            originalSource.activeSchema().references());
    var child =
        new RuntimeRecordModel(
            "detail",
            "app",
            "detail-form",
            1,
            childSchema,
            new PhysicalSchemaTarget(
                "detail",
                new RuntimeMetadataTarget("db", "catalog", "schema", "detail"),
                originalSource.target().referenceTargets()));
    var old = original.plan().bindings().get("customer");
    var rule =
        new RecordReferenceRule(
            "1",
            "customer",
            new RecordValidationField("lines", "customer"),
            "target",
            "app",
            "target-form",
            "target-page",
            null,
            null,
            null,
            old.rule().mappings());
    var rootReference = new RecordSchemaReference(1, codec.encode(rootSchema).checksum());
    var childReference = new RecordSchemaReference(1, codec.encode(childSchema).checksum());
    var definition =
        new RecordCalculationDefinition(
            "1",
            Map.of(),
            Map.of(
                "lines",
                new org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationScope(
                    "detail", childReference, Map.of("upper", "UPPER([address])"))),
            null,
            null,
            null,
            null,
            List.of(rule));
    var installed =
        new RecordCalculationStore.Installed(
            1,
            new RecordCalculationPolicy(
                1,
                "app",
                "source-form",
                rootReference,
                definition,
                Map.of("detail", Set.of("address", "upper"))));
    var plan =
        new RecordReferenceAdmission.Plan(
            installed,
            Map.of("source", root, "detail", child),
            Map.of("customer", new RecordReferenceAdmission.Binding(rule, "detail", old.target())));
    var rootAccess =
        new RecordAccessContext(
            original.scope().access().actor(),
            "source-page",
            Set.of("id"),
            Set.of(),
            Set.of(),
            null,
            null,
            1);
    var childCodes = Set.copyOf(child.requestedFieldCodes());
    var childAccess =
        new RecordAccessContext(
            rootAccess.actor(), "source-page", childCodes, childCodes, Set.of(), null, null, 1);
    var rootScope = new RecordCalculationWriteSupport.Scope(root, rootAccess);
    var childScope = new RecordCalculationWriteSupport.Scope(child, childAccess);
    var resolver = mock(RuntimeRecordModelResolver.class);
    when(resolver.resolve(rootAccess.actor(), "source", rootReference)).thenReturn(root);
    when(resolver.resolve(rootAccess.actor(), "detail", childReference)).thenReturn(child);
    var calculations =
        new RecordCalculationWriteSupport(
            resolver, codec, new RecordSqlCompiler(new StandardLogicalValueConverter()));
    var binding = calculations.bind(rootAccess.actor(), root, installed);
    var detail =
        new org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordSet(
            new org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordModel(
                "detail", childReference),
            List.of(
                new org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordRow(
                    null, null, Map.of("customer_id", TARGET)),
                new org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordRow(
                    null, null, Map.of())));
    var observation = plan.mapping("customer").observe(new RecordView(TARGET, 7, Map.of()));
    var plain =
        new OwnedRecordCommand(
            new RecordTransactionCommand(
                "source",
                rootReference,
                List.of(new RecordCreateMutation("root", Map.of())),
                30000,
                "detail-mapping",
                1),
            List.of(detail));
    var command =
        new MappedOwnedRecordCommand(
            "1",
            plain,
            null,
            List.of(
                new RecordReferenceMappingConfirmation(
                    "customer", "detail", 0, null, observation, List.of())));
    RecordReferenceMappingWrite.TargetReader targets =
        (target, ids, selected) ->
            Map.of(
                TARGET,
                new RecordReferenceMappingTargets.Captured(
                    new RecordView(TARGET, 7, Map.of("address", "hello😀")),
                    Map.of("address", List.of(original.requirement()))));
    var loaded =
        RecordReferenceMappingWrite.load(
            plan, command, Map.of("source", rootScope, "detail", childScope), targets);
    var operation =
        RecordOperationIdentity.of(
            rootAccess.actor(),
            plain.parent(),
            new RecordRequestFingerprint(new ObjectMapper()).of(command));
    var prepared =
        calculations.prepareMapped(
            binding,
            rootScope,
            Map.of("detail", childScope),
            plain,
            ROW,
            operation,
            EMPTY,
            Instant.now(),
            loaded);
    assertThat(prepared.statements()).hasSize(3);
    assertThat(prepared.statements().get(1).parameters()).contains("hello😀", "HELLO😀");
    assertThat(prepared.statements().get(2).parameters()).doesNotContain("hello😀", "HELLO😀");
    assertThat(prepared.copies())
        .singleElement()
        .satisfies(
            copy -> {
              assertThat(copy.key().modelId()).isEqualTo("detail");
              assertThat(copy.key().recordId()).isEqualTo(prepared.statements().get(1).recordId());
            });
    var moved =
        new MappedOwnedRecordCommand(
            "1",
            plain,
            null,
            List.of(
                new RecordReferenceMappingConfirmation(
                    "customer", "detail", 1, null, observation, List.of())));
    var wrong =
        RecordReferenceMappingWrite.load(
            plan, moved, Map.of("source", rootScope, "detail", childScope), targets);
    assertThatThrownBy(
            () ->
                calculations.prepareMapped(
                    binding,
                    rootScope,
                    Map.of("detail", childScope),
                    plain,
                    ROW,
                    operation,
                    EMPTY,
                    Instant.now(),
                    wrong))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void selectedRequiredDefaultsDeferMissingValuesWithoutMakingThemServerOnlyFields() {
    var f = fixture(RecordReferenceMapping.Mode.EDITABLE_DEFAULT);
    var command = f.command(Map.of("customer_id", TARGET), f.observe(), List.of("copy"));
    var original = f.scope().model();
    var fields =
        original.activeSchema().fields().stream()
            .map(
                field ->
                    field.fieldId().equals("address")
                        ? new LogicalFieldDefinition(
                            field.fieldId(),
                            field.code(),
                            field.displayName(),
                            field.type(),
                            false,
                            field.length(),
                            field.precision(),
                            field.scale(),
                            field.defaultValue())
                        : field)
            .toList();
    var schema =
        new PhysicalSchemaDefinition(
            DnaRuntimeApiVersion.CURRENT,
            fields,
            List.of(),
            null,
            true,
            original.activeSchema().references());
    var required =
        new RuntimeRecordModel(
            original.physicalModelId(),
            original.appId(),
            original.formId(),
            1,
            schema,
            original.target());
    var compiler = new RecordSqlCompiler(new StandardLogicalValueConverter());
    var mutation = command.command().parent().mutations().getFirst();
    assertThatThrownBy(
            () -> compiler.validateIntent(required, f.scope().access(), mutation, Set.of("upper")))
        .isInstanceOf(DnaRuntimeException.class);
    var deferred = RecordReferenceMappingIntent.defaults(f.binding(), command, "source", null);
    assertThat(deferred).containsExactly("address");
    compiler.validateIntent(required, f.scope().access(), mutation, Set.of("upper"), deferred);
    compiler.validateIntent(
        required,
        f.scope().access(),
        new RecordCreateMutation("manual", Map.of("address", "manual")),
        Set.of("upper"),
        deferred);
    var unknown = f.command(Map.of("customer_id", TARGET), f.observe(), List.of("unknown"));
    assertThatThrownBy(
            () -> RecordReferenceMappingIntent.defaults(f.binding(), unknown, "source", null))
        .isInstanceOf(DnaRuntimeException.class);
    assertThat(RecordReferenceMappingIntent.defaults(f.binding(), command, "detail", 0)).isEmpty();
  }

  @Test
  void targetReadCannotBorrowAnotherActorsOrTenantsPreparedAuthority() {
    var f = fixture(RecordReferenceMapping.Mode.SERVER_SNAPSHOT);
    var command = f.command(Map.of("customer_id", TARGET), f.observe(), List.of());
    var access = f.scope().access();
    for (var actor :
        List.of(new RuntimeActor("tenant", "another"), new RuntimeActor("another", "actor"))) {
      var foreign =
          new RecordAccessContext(
              actor,
              access.resourceCode(),
              access.readableFields(),
              access.writableFields(),
              access.maskedFields(),
              access.rowScope(),
              access.organizationId(),
              access.resourceGeneration());
      assertThatThrownBy(
              () ->
                  RecordReferenceMappingWrite.load(
                      f.plan(),
                      command,
                      Map.of(
                          "source",
                          new RecordCalculationWriteSupport.Scope(f.scope().model(), foreign)),
                      (target, ids, fields) -> {
                        throw new AssertionError(
                            "Foreign prepared authority must fail before any value read");
                      }))
          .isInstanceOf(DnaRuntimeException.class);
    }
  }

  private static Fixture fixture(RecordReferenceMapping.Mode mode) {
    var schemas = new PhysicalSchemaCodec(new ObjectMapper());
    var sourceLocation = new RuntimeMetadataTarget("db", "catalog", "schema", "source");
    var targetLocation = new RuntimeMetadataTarget("db", "catalog", "schema", "target");
    var schema =
        new PhysicalSchemaDefinition(
            DnaRuntimeApiVersion.CURRENT,
            List.of(
                field("customer", "customer_id", LogicalFieldType.UUID),
                field("address", "address", LogicalFieldType.STRING),
                field("upper", "upper", LogicalFieldType.STRING)),
            List.of(),
            null,
            true,
            List.of(
                new LogicalReferenceDefinition(
                    "customer",
                    "customer",
                    "target",
                    false,
                    List.of(new LogicalReferenceField("address", LogicalFieldType.STRING)))));
    var source =
        new RuntimeRecordModel(
            "source",
            "app",
            "source-form",
            1,
            schema,
            new PhysicalSchemaTarget("source", sourceLocation, Map.of("target", targetLocation)));
    var target =
        new RuntimeRecordModel(
            "target",
            "app",
            "target-form",
            1,
            new PhysicalSchemaDefinition(
                DnaRuntimeApiVersion.CURRENT,
                List.of(field("address", "address", LogicalFieldType.STRING)),
                List.of(),
                null,
                true),
            new PhysicalSchemaTarget("target", targetLocation));
    var rule =
        new RecordReferenceRule(
            "1",
            "customer",
            new RecordValidationField("parent", "customer"),
            "target",
            "app",
            "target-form",
            "target-page",
            null,
            null,
            null,
            List.of(
                new RecordReferenceMapping(
                    "copy",
                    "address",
                    "address",
                    mode,
                    RecordReferenceConversion.IDENTITY,
                    RecordReferenceMapping.NullPolicy.CLEAR)));
    var definition =
        new RecordCalculationDefinition(
            "1",
            Map.of("upper", "UPPER([address])"),
            Map.of(),
            null,
            null,
            null,
            null,
            List.of(rule));
    var policy =
        new RecordCalculationStore.Installed(
            1,
            new RecordCalculationPolicy(
                1,
                "app",
                "source-form",
                new RecordSchemaReference(1, schemas.encode(schema).checksum()),
                definition,
                Map.of("source", Set.of("address", "upper"))));
    var actor = new RuntimeActor("tenant", "actor");
    var access =
        new RecordAccessContext(
            actor,
            "source-page",
            Set.of("customer_id", "address", "upper"),
            Set.of("customer_id", "address"),
            Set.of(),
            null,
            null,
            1);
    var targetAccess =
        new RecordAccessContext(
            actor, "target-page", Set.of("id", "address"), Set.of(), Set.of(), null, null, 1);
    var plan =
        new RecordReferenceAdmission.Plan(
            policy,
            Map.of("source", source),
            Map.of(
                "customer",
                new RecordReferenceAdmission.Binding(
                    rule,
                    "source",
                    new RecordReferenceAdmission.Target(
                        target,
                        targetAccess,
                        new RecordReferenceAdmission.TargetSnapshot(0, null)))));
    var resolver = mock(RuntimeRecordModelResolver.class);
    when(resolver.resolve(actor, "source", policy.policy().parentSchema())).thenReturn(source);
    var calculations =
        new RecordCalculationWriteSupport(
            resolver, schemas, new RecordSqlCompiler(new StandardLogicalValueConverter()));
    return new Fixture(
        plan,
        new RecordCalculationWriteSupport.Scope(source, access),
        calculations,
        calculations.bind(actor, source, policy));
  }

  private static LogicalFieldDefinition field(String id, String code, LogicalFieldType type) {
    return new LogicalFieldDefinition(
        id, code, code, type, true, type == LogicalFieldType.STRING ? 100 : null, null, null, null);
  }

  private record Fixture(
      RecordReferenceAdmission.Plan plan,
      RecordCalculationWriteSupport.Scope scope,
      RecordCalculationWriteSupport calculations,
      RecordCalculationWriteSupport.Binding binding) {
    RecordReferenceMappingObservation observe() {
      return plan.mapping("customer").observe(new RecordView(TARGET, 7, Map.of()));
    }

    MappedOwnedRecordCommand command(
        Map<String, Object> values,
        RecordReferenceMappingObservation observation,
        List<String> defaults) {
      return new MappedOwnedRecordCommand(
          "1",
          new OwnedRecordCommand(
              new RecordTransactionCommand(
                  "source",
                  plan.policy().policy().parentSchema(),
                  List.of(new RecordCreateMutation("create", values)),
                  30000,
                  "mapping-test",
                  1),
              List.of()),
          null,
          List.of(
              new RecordReferenceMappingConfirmation(
                  "customer", null, null, null, observation, defaults)));
    }

    RecordOperationIdentity operation(MappedOwnedRecordCommand command) {
      return RecordOperationIdentity.of(
          scope.access().actor(),
          command.command().parent(),
          new RecordRequestFingerprint(new ObjectMapper()).of(command));
    }

    RecordReferenceReadRequirement requirement() {
      return new RecordReferenceReadRequirement(
          "target", "app", "target-form", "target-page", TARGET, null, null, List.of("address"));
    }

    RecordReferenceMappingWrite load(MappedOwnedRecordCommand command) {
      return RecordReferenceMappingWrite.load(
          plan,
          command,
          Map.of("source", scope),
          (target, ids, fields) ->
              Map.of(
                  TARGET,
                  new RecordReferenceMappingTargets.Captured(
                      new RecordView(TARGET, 7, Map.of("address", "hello😀")),
                      Map.of("address", List.of(requirement())))));
    }
  }
}
