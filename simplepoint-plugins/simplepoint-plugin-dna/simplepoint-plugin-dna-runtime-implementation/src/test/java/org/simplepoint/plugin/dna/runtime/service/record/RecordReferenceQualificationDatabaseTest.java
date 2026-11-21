package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.ResourceDataScope;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationCondition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValidationField;
import org.simplepoint.plugin.dna.runtime.api.record.RecordValueConditionOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValue;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueKind;
import org.simplepoint.plugin.dna.runtime.configuration.DnaRuntimeExecutionProperties;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelResourceBindingEntity;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelResourceBindingRepository;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordJournalProvisioner;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;

/** Real installed policies, ordinary transaction service, Calcite and physical FK on PG/MySQL. */
class RecordReferenceQualificationDatabaseTest {
  @AfterEach
  void cleanupContext() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    ResourcePolicyContext.clear();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void changedReferencesRequireCurrentQualificationWhileRetainedReferencesSurviveRevocation(
      boolean postgres) throws Exception {
    try (var f = new Fixture(postgres)) {
      String customer = f.customer("ACTIVE");
      var created =
          f.execute(
              new RecordCreateMutation(
                  "create", Map.of("customer_id", customer, "status", "DRAFT")));
      String trade = created.recordId();
      f.base.save(
          f.base.customer,
          f.base.update(f.base.customer, customer, 0, Map.of("status", "INACTIVE")));
      assertThat(
              f.execute(new RecordUpdateMutation("note", trade, 0, Map.of("note", "retained")))
                  .version())
          .isEqualTo(1);
      conflict(
          () ->
              f.execute(
                  new RecordUpdateMutation("submit", trade, 1, Map.of("status", "SUBMITTED"))));
      assertThat(f.base.value(f.base.trade, trade, "status")).isEqualTo("DRAFT");
      f.authorize(false, "SELF", Map.of());
      assertThat(
              f.execute(
                      new RecordUpdateMutation(
                          "retained", trade, 1, Map.of("note", "no target grant")))
                  .version())
          .isEqualTo(2);
      String replacement = f.customer("ACTIVE");
      conflict(
          () ->
              f.execute(
                  new RecordUpdateMutation(
                      "retarget", trade, 2, Map.of("customer_id", replacement))));
      assertThat(f.base.value(f.base.trade, trade, "customer_id")).isEqualTo(customer);
      f.authorize(true, "SELF", Map.of());
      assertThat(
              f.execute(
                      new RecordUpdateMutation(
                          "retarget", trade, 2, Map.of("customer_id", replacement)))
                  .version())
          .isEqualTo(3);
      assertThat(f.base.value(f.base.trade, trade, "customer_id")).isEqualTo(replacement);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void incompatibleTargetConditionDoesNotBecomeDurableBusinessRejection(boolean postgres)
      throws Exception {
    // Deliberately inject an obsolete/invalid installed declaration below the publication boundary.
    // Its execution error must not be confused with a valid rule rejecting one business record.
    var invalid =
        new RecordValidationCondition(
            RecordValueConditionOperator.EQUALS,
            new RecordValidationField("parent", "status"),
            List.of(new LogicalValue(LogicalValueKind.INTEGER, "1")),
            List.of());
    try (var f =
        new Fixture(
            postgres,
            new org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine(),
            invalid)) {
      String customer = f.customer("ACTIVE");
      var command =
          f.command(
              "incompatible-policy",
              List.of(new RecordCreateMutation("create", Map.of("customer_id", customer))));
      assertThatThrownBy(() -> f.service.execute(command))
          .isInstanceOfSatisfying(
              DnaRuntimeException.class,
              failure ->
                  assertThat(failure.errorCode())
                      .isEqualTo(DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE));
      assertThat(f.base.count(f.base.trade)).isZero();
      assertThat(f.service.findResult(command)).isNull();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void targetRowAndFieldScopeCannotBeBorrowedFromTheSource(boolean postgres) throws Exception {
    try (var f = new Fixture(postgres)) {
      String customer = f.customer("ACTIVE");
      f.authorize(true, "DENY", Map.of());
      conflict(
          () -> f.execute(new RecordCreateMutation("row-denied", Map.of("customer_id", customer))));
      for (String protection : List.of("HIDDEN", "MASKED")) {
        f.authorize(
            true,
            "ALL",
            Map.of(
                RecordPermissionModel.key(f.base.customer.physicalModelId())
                    + "#"
                    + RecordPermissionModel.field("status"),
                protection));
        conflict(
            () ->
                f.execute(
                    new RecordCreateMutation("field-denied", Map.of("customer_id", customer))));
      }
      assertThat(f.base.count(f.base.trade)).isZero();
      f.authorize(true, "SELF", Map.of());
      assertThat(
              f.execute(new RecordCreateMutation("allowed", Map.of("customer_id", customer)))
                  .affectedRows())
          .isEqualTo(1);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void batchQualificationFailureRollsBackAndItsDurableRejectionCannotRevive(boolean postgres)
      throws Exception {
    try (var f = new Fixture(postgres)) {
      String active = f.customer("ACTIVE");
      String inactive = f.customer("INACTIVE");
      var command =
          f.command(
              "rejected",
              List.of(
                  new RecordCreateMutation("first", Map.of("customer_id", active)),
                  new RecordCreateMutation("second", Map.of("customer_id", inactive))));
      conflict(() -> f.service.execute(command));
      assertThat(f.base.count(f.base.trade)).isZero();
      f.base.save(
          f.base.customer, f.base.update(f.base.customer, inactive, 0, Map.of("status", "ACTIVE")));
      conflict(() -> f.service.execute(command));
      conflict(() -> f.service.findResult(command));
      assertThat(f.base.count(f.base.trade)).isZero();
      assertThat(f.service.execute(f.command("corrected", command.mutations())).results())
          .hasSize(2);
      assertThat(f.base.count(f.base.trade)).isEqualTo(2);
    }
  }

  private static void conflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action)
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo(DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void qualificationAndTargetStateChangeCannotRaceAcrossTheSourceCommit(boolean postgres)
      throws Exception {
    var engine =
        org.mockito.Mockito.spy(
            new org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine());
    try (var f = new Fixture(postgres, engine);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      String customer = f.customer("ACTIVE");
      var inserted = new java.util.concurrent.CountDownLatch(1);
      var release = new java.util.concurrent.CountDownLatch(1);
      org.mockito.Mockito.doAnswer(
              call -> {
                var request =
                    (org.simplepoint.data.calcite.core.query.CalciteUpdateRequest)
                        call.getArgument(0);
                Object result = call.callRealMethod();
                if (request.sql().startsWith("INSERT INTO")
                    && request.sql().contains(f.base.trade.target().metadataTarget().tableName())) {
                  inserted.countDown();
                  assertThat(release.await(25, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                }
                return result;
              })
          .when(engine)
          .executeUpdate(any(), any());
      var write =
          workers.submit(
              () -> {
                f.authorize(true, "SELF", Map.of());
                try {
                  return f.execute(
                      new RecordCreateMutation("create", Map.of("customer_id", customer)));
                } finally {
                  RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
                  ResourcePolicyContext.clear();
                }
              });
      try {
        assertThat(inserted.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var change =
            workers.submit(
                () -> {
                  f.base.save(
                      f.base.customer,
                      f.base.update(f.base.customer, customer, 0, Map.of("status", "INACTIVE")));
                  return true;
                });
        f.base.awaitRowLock(f.base.customer);
        assertThat(change.isDone()).isFalse();
        release.countDown();
        var result = write.get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(change.get(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(f.base.value(f.base.trade, result.recordId(), "customer_id"))
            .isEqualTo(customer);
        assertThat(f.base.value(f.base.customer, customer, "status")).isEqualTo("INACTIVE");
      } finally {
        release.countDown();
      }
    }
  }

  private static RecordValidationCondition equalsValue(String field, String value) {
    return new RecordValidationCondition(
        RecordValueConditionOperator.EQUALS,
        new RecordValidationField("parent", field),
        List.of(new LogicalValue(LogicalValueKind.STRING, value)),
        List.of());
  }

  static final class Fixture implements AutoCloseable {
    final RecordReferenceIntegrityDatabaseTest.Fixture base;
    final RecordTransactionServiceImpl service;
    final RecordSchemaReference schema;
    final RecordReferenceSnapshotReader historyReader;
    final OwnedRecordService ownedService;
    final RecordQueryServiceImpl queries;

    Fixture(boolean postgres) throws Exception {
      this(postgres, new org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine());
    }

    Fixture(boolean postgres, org.simplepoint.data.calcite.core.query.CalciteQueryEngine engine)
        throws Exception {
      this(postgres, engine, equalsValue("status", "ACTIVE"));
    }

    Fixture(
        boolean postgres,
        org.simplepoint.data.calcite.core.query.CalciteQueryEngine engine,
        RecordValidationCondition eligibility)
        throws Exception {
      this(postgres, engine, eligibility, false);
    }

    Fixture(
        boolean postgres,
        org.simplepoint.data.calcite.core.query.CalciteQueryEngine engine,
        RecordValidationCondition eligibility,
        boolean captureLabel)
        throws Exception {
      this(
          postgres, engine, eligibility, captureLabel, java.util.function.UnaryOperator.identity());
    }

    Fixture(
        boolean postgres,
        org.simplepoint.data.calcite.core.query.CalciteQueryEngine engine,
        RecordValidationCondition eligibility,
        boolean captureLabel,
        java.util.function.UnaryOperator<RecordCalculationDefinition> definitions)
        throws Exception {
      base = new RecordReferenceIntegrityDatabaseTest.Fixture(postgres, false, true, engine);
      var codec = new PhysicalSchemaCodec(new ObjectMapper().findAndRegisterModules());
      schema = new RecordSchemaReference(1, codec.encode(base.trade.activeSchema()).checksum());
      var rule =
          new RecordReferenceRule(
              "1",
              "trade-customer",
              new RecordValidationField("parent", "customer"),
              base.customer.physicalModelId(),
              "app",
              "customers",
              "customers",
              eligibility,
              equalsValue("status", "SUBMITTED"),
              captureLabel ? "status" : null);
      var definition =
          definitions.apply(
              new RecordCalculationDefinition(
                  "1", Map.of(), Map.of(), null, null, null, null, List.of(rule)));
      var protectedFields = new java.util.LinkedHashSet<String>();
      definition
          .referencesOrEmpty()
          .forEach(
              reference ->
                  reference
                      .mappings()
                      .forEach(mapping -> protectedFields.add(mapping.destinationFieldId())));
      if (!protectedFields.isEmpty()) {
        protectedFields.addAll(definition.formulas().keySet());
      }
      var policy =
          new RecordCalculationPolicy(
              1,
              "app",
              "trades",
              schema,
              definition,
              protectedFields.isEmpty()
                  ? Map.of()
                  : Map.of(base.trade.physicalModelId(), Set.copyOf(protectedFields)));
      var command =
          new RecordWritePolicyPublicationCommand(
              base.trade.physicalModelId(),
              "app",
              "trades",
              schema,
              0,
              1,
              "install-reference",
              "c".repeat(64),
              Map.of(),
              30_000,
              Map.of(),
              definition);
      var key = new RecordWriteFenceKey("tenant-a", base.trade.physicalModelId());
      var intent =
          new RecordPublicationIntent(
              1,
              key,
              command,
              "c".repeat(64),
              "0".repeat(64),
              "publisher",
              Instant.now().truncatedTo(ChronoUnit.MICROS).toString(),
              Map.of(
                  base.trade.physicalModelId(),
                  new RecordPublicationIntent.Binding(
                      schema, schema, base.trade.target().metadataTarget(), 0)),
              null);
      base.backend.publication(
          base.trade,
          List.of(),
          key,
          30_000,
          session -> {
            session.write(
                new RecordCalculationStore().insert(key, 1, "publisher", Instant.now(), policy));
            session.activate(intent);
            return null;
          });
      new RecordJournalProvisioner(base.schemaBackend).prepare(base.trade.target());
      var models = mock(RuntimeRecordModelResolver.class);
      when(models.resolve(any(), anyString(), any()))
          .thenAnswer(
              call -> {
                String id = call.getArgument(1);
                if (id.equals(base.trade.physicalModelId())) {
                  return base.trade;
                }
                if (id.equals(base.customer.physicalModelId())) {
                  return base.customer;
                }
                throw new AssertionError("Unknown test model");
              });
      when(models.resolveCurrent(any(), anyString())).thenReturn(base.customer);
      var bindings = mock(PhysicalModelResourceBindingRepository.class);
      when(bindings.findActive(anyString(), anyString(), anyString()))
          .thenReturn(Optional.of(new PhysicalModelResourceBindingEntity()));
      var access = new RuntimeRecordAccessPolicy(bindings);
      // Generation capture is isolated here; real multi-resource control locks have their own PG
      // test.
      var resources = mock(RecordResourceFence.class);
      when(resources.generation(anyString(), anyString(), anyString())).thenReturn(1L);
      access.setResourceFence(resources);
      var calculations = new RecordCalculationWriteSupport(models, codec, base.compiler);
      historyReader =
          new RecordReferenceSnapshotReader(
              models,
              access,
              base.compiler,
              base.backend,
              new RecordCalculationAccess(calculations, access));
      var context = new RuntimeRequestContext();
      base.backend.setReferenceAdmission(
          new RecordReferenceAdmission(
              context,
              models,
              access,
              new RecordCalculationAccess(calculations, access),
              base.compiler,
              codec),
          base.compiler);
      service =
          new RecordTransactionServiceImpl(
              context,
              models,
              access,
              base.compiler,
              base.backend,
              mock(RecordAuditSupport.class),
              new RecordAdmissionController(new DnaRuntimeExecutionProperties()),
              mock(RecordExecutionObserver.class),
              new RecordRequestFingerprint(new ObjectMapper().findAndRegisterModules()),
              calculations);
      queries =
          new RecordQueryServiceImpl(
              context,
              models,
              access,
              base.compiler,
              base.backend,
              mock(RecordAuditSupport.class),
              new RecordAdmissionController(new DnaRuntimeExecutionProperties()),
              mock(RecordExecutionObserver.class),
              new RecordCalculationAccess(calculations, access));
      ownedService =
          new OwnedRecordService(
              context,
              models,
              access,
              base.compiler,
              base.backend,
              new RecordRequestFingerprint(new ObjectMapper().findAndRegisterModules()),
              new RecordAdmissionController(new DnaRuntimeExecutionProperties()),
              mock(RecordAuditSupport.class),
              mock(RecordExecutionObserver.class),
              calculations,
              new RecordCalculationAccess(calculations, access),
              codec);
      authorize(true, "SELF", Map.of());
    }

    void authorize(boolean targetGranted, String targetScope, Map<String, String> targetFields) {
      authorize("user", targetGranted, targetScope, targetFields, Map.of());
    }

    void authorize(
        String actor,
        boolean targetGranted,
        String targetScope,
        Map<String, String> targetFields,
        Map<String, String> sourceFields) {
      var authorization = new AuthorizationContext();
      authorization.setUserId(actor);
      authorization.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
      authorization.setResources(targetGranted ? Set.of("trades", "customers") : Set.of("trades"));
      authorization.setDataScopeType("ALL");
      authorization.setResourceDataScopes(
          targetGranted
              ? Map.of(
                  "trades",
                  new ResourceDataScope("ALL", Set.of(), false),
                  "customers",
                  new ResourceDataScope(targetScope, Set.of(), false))
              : Map.of("trades", new ResourceDataScope("ALL", Set.of(), false)));
      authorization.setResourceFieldPermissions(
          targetGranted
              ? Map.of("trades", sourceFields, "customers", targetFields)
              : Map.of("trades", sourceFields));
      RequestContextHolder.setContext(
          RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authorization);
      ResourcePolicyContext.set("trades");
    }

    String customer(String status) {
      var row = base.create(base.customer, Map.of("status", status));
      base.save(base.customer, row);
      return row.recordId();
    }

    RecordCommandResult execute(RecordMutation mutation) {
      return service
          .execute(command(UUID.randomUUID().toString(), List.of(mutation)))
          .results()
          .getFirst()
          .result();
    }

    RecordTransactionCommand command(String key, List<RecordMutation> mutations) {
      return new RecordTransactionCommand(
          base.trade.physicalModelId(), schema, mutations, 30_000, key, 1);
    }

    @Override
    public void close() throws Exception {
      base.close();
    }
  }
}
