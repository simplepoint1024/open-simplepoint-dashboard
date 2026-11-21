package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDocument;
import org.simplepoint.plugin.app.form.api.model.FormFieldDefinition;
import org.simplepoint.plugin.app.form.api.model.FormFieldType;
import org.simplepoint.plugin.app.form.api.model.FormStorageMode;
import org.simplepoint.plugin.app.form.api.model.relation.FormCollectionReplacement;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationCardinality;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationDefinition;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationField;
import org.simplepoint.plugin.app.form.api.model.relation.FormRelationTarget;
import org.simplepoint.plugin.app.form.entity.ApplicationFormVersionEntity;
import org.simplepoint.plugin.app.form.service.FormCalculationPublicationProjection;
import org.simplepoint.plugin.app.form.service.FormDetailSchemaCoordinator;
import org.simplepoint.plugin.app.form.service.FormDnaSchemaPublicationCoordinator.DnaSchemaPreparationRequest;
import org.simplepoint.plugin.app.form.service.FormDocumentCodec;
import org.simplepoint.plugin.app.form.service.FormRelationBinding;
import org.simplepoint.plugin.app.form.service.FormStorageProjectionCompiler;
import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.context.DnaResourcePolicy;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceProjectionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordObservationQuery;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCollectionRebindMigration;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCollectionRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCountQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordLookupQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceCollectionMigration;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;
import org.simplepoint.plugin.dna.runtime.configuration.DnaFormRuntimeRemoteProperties;
import org.simplepoint.plugin.dna.runtime.service.remote.DnaFormRuntimeCallerGuard;
import org.simplepoint.plugin.dna.runtime.service.remote.DnaOwnedRecordServiceImpl;

@EnabledIfEnvironmentVariable(
    named = "DNA_RUNTIME_TEST_POSTGRES_URL",
    matches = "jdbc:postgresql:.*")
class FormDnaCollectionDatabaseTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void convertsAnAppCompiledRequiredReferenceToACollection(boolean postgres) throws Exception {
    try (var fixture = new FormDnaPublicationFixture(postgres)) {
      var actor = FormDnaPublicationFixture.actor("tenant-a");
      var codec = new FormDocumentCodec(fixture.mapper);
      var storage = new FormStorageProjectionCompiler(fixture.mapper);
      var calculation =
          new FormCalculationPublicationProjection(
              codec, storage, fixture.mapper, Clock.systemUTC());
      var targetStorage =
          storage.compile(
              new ApplicationFormDocument("1", List.of(), List.of(), List.of(), List.of()),
              List.of(),
              true);
      var target =
          fixture.publications.prepare(
              actor,
              new DnaSchemaPreparationRequest(
                  "employee-app",
                  "employees",
                  "Employees",
                  "default",
                  null,
                  targetStorage.schema(),
                  targetStorage.checksum(),
                  false));
      var identity = new FormRelationTarget("employee-app", "employees", "employee-resource");
      var original =
          new FormRelationDefinition(
              "1",
              "main-member",
              FormRelationCardinality.MANY_TO_ONE,
              new FormRelationField(null, "lead"),
              identity);
      var field =
          new FormFieldDefinition(
              "lead",
              "lead_id",
              "Lead",
              "Lead",
              FormFieldType.UUID,
              FormStorageMode.REFERENCE,
              true,
              true,
              null,
              null,
              null,
              null,
              List.of());
      var before =
          new ApplicationFormDocument(
              "1",
              List.of(field),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              null,
              List.of(original));
      var oldBinding =
          new FormRelationBinding(
              "main-member", identity, "employees-page", "employees", target.physicalModelId());
      var beforeStorage = storage.compile(before, List.of(oldBinding), true);
      assertFalse(
          beforeStorage.schema().fields().stream()
              .filter(item -> item.fieldId().equals("lead"))
              .findFirst()
              .orElseThrow()
              .nullable());
      var root =
          fixture.publications.prepare(
              actor,
              new DnaSchemaPreparationRequest(
                  "project-app",
                  "projects",
                  "Projects",
                  "default",
                  null,
                  beforeStorage.schema(),
                  beforeStorage.checksum(),
                  false));
      fixture.dna.projectModelResource(
          actor,
          new PhysicalModelResourceProjectionCommand(
              root.physicalModelId(), "projects", "app:projects", 1));
      fixture.dna.projectModelResource(
          actor,
          new PhysicalModelResourceProjectionCommand(
              target.physicalModelId(), "employees", "app:employees", 1));
      var beforeVersion =
          relationVersion(
              codec, before, beforeStorage, root.physicalModelVersion(), List.of(oldBinding), null);
      var oldSchema =
          new RecordSchemaReference(root.physicalModelVersion(), beforeStorage.checksum());
      var initial =
          new RecordWritePolicyPublicationCommand(
              root.physicalModelId(),
              "project-app",
              "projects",
              oldSchema,
              0,
              1,
              "initial-lead",
              beforeVersion.getChecksum(),
              Map.of(),
              30_000,
              Map.of(),
              calculation.compile(beforeVersion, root.physicalModelId(), false));
      finishPublication(fixture, actor, initial);
      var targetSchema =
          new RecordSchemaReference(target.physicalModelVersion(), targetStorage.checksum());
      var createdTarget =
          fixture
              .dna
              .executeRecordTransaction(
                  policy("employees", "ALL", Map.of(), false),
                  creates(target.physicalModelId(), targetSchema, 0, 1))
              .results()
              .getFirst()
              .result()
              .recordId();
      var user = policy("projects", "ALL", Map.of(), true);
      var create =
          new RecordTransactionCommand(
              root.physicalModelId(),
              oldSchema,
              List.of(
                  new RecordCreateMutation(
                      "project", Map.of("record_state", "DRAFT", "lead_id", createdTarget))),
              30_000,
              "create-project-with-lead",
              1L);
      var project =
          fixture
              .dna
              .executeRecordTransaction(user, create)
              .results()
              .getFirst()
              .result()
              .recordId();

      var collection =
          new FormRelationDefinition(
              "1", "members", FormRelationCardinality.MANY_TO_MANY, null, identity);
      var after =
          new ApplicationFormDocument(
              "1",
              List.of(field),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              null,
              List.of(original.retireBy(collection.relationId()), collection));
      var bindings =
          List.of(
              oldBinding,
              new FormRelationBinding(
                  "members", identity, "employees-page", "employees", target.physicalModelId()));
      var afterStorage = storage.compile(after, bindings, true);
      var changed =
          fixture.publications.prepare(
              actor,
              new DnaSchemaPreparationRequest(
                  "project-app",
                  "projects",
                  "Projects",
                  "default",
                  root.physicalModelId(),
                  afterStorage.schema(),
                  afterStorage.checksum(),
                  false));
      assertTrue(changed.active());
      var edges =
          new FormDetailSchemaCoordinator(fixture.publications, storage, fixture.mapper)
              .prepareCollections(
                  actor, "project-app", "projects", root.physicalModelId(), after, bindings, false);
      var candidate =
          relationVersion(
              codec, after, afterStorage, changed.physicalModelVersion(), bindings, edges);
      var schema =
          new RecordSchemaReference(changed.physicalModelVersion(), afterStorage.checksum());
      var beforeCutover =
          assertThrows(
              DnaRuntimeException.class,
              () ->
                  fixture.dna.executeRecordTransaction(
                      user,
                      new RecordTransactionCommand(
                          root.physicalModelId(),
                          schema,
                          creates(root.physicalModelId(), schema, 1, 1).mutations(),
                          30_000,
                          "before-cutover-missing-lead",
                          1L)));
      assertEquals(DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT, beforeCutover.errorCode());
      var command =
          new RecordWritePolicyPublicationCommand(
              root.physicalModelId(),
              "project-app",
              "projects",
              schema,
              1,
              2,
              "convert-lead",
              candidate.getChecksum(),
              Map.of(),
              30_000,
              Map.of(),
              calculation.compile(candidate, root.physicalModelId(), true),
              List.of(),
              new RecordReferenceCollectionMigration("main-member", "members"));
      finishPublication(fixture, actor, command);
      var properties = new DnaFormRuntimeRemoteProperties();
      properties.setAllowedCallers(Set.of("app-form-test"));
      var rpc =
          new DnaOwnedRecordServiceImpl(
              new DnaFormRuntimeCallerGuard(properties), fixture.recordServices.owned);
      var members =
          rpc.pageCollection(
              user,
              page(
                  root.physicalModelId(),
                  schema,
                  target.physicalModelId(),
                  targetSchema,
                  project,
                  0,
                  false,
                  false));
      assertEquals(
          List.of(createdTarget), members.records().stream().map(record -> record.id()).toList());
      var current =
          fixture.dna.getRecord(
              user,
              new RecordLookupQuery(
                  root.physicalModelId(), oldSchema, project, List.of("lead_id")));
      assertEquals(null, current.values().get("lead_id"));
      assertEquals(1L, current.version());
      var legacyCreate = creates(root.physicalModelId(), oldSchema, 2, 1);
      assertNotNull(
          fixture
              .dna
              .executeRecordTransaction(user, legacyCreate)
              .results()
              .getFirst()
              .result()
              .recordId());
      var ownedCreate =
          new RecordTransactionCommand(
              root.physicalModelId(),
              oldSchema,
              legacyCreate.mutations(),
              30_000,
              "legacy-owned-create",
              2L);
      assertNotNull(rpc.execute(user, new OwnedRecordCommand(ownedCreate, List.of())).recordId());
      assertThrows(
          DnaRuntimeException.class,
          () ->
              fixture.dna.executeRecordTransaction(
                  user,
                  new RecordTransactionCommand(
                      root.physicalModelId(),
                      oldSchema,
                      List.of(
                          new RecordUpdateMutation(
                              "late", project, 1, Map.of("lead_id", createdTarget))),
                      30_000,
                      "late-reference",
                      2L)));

      var contractor =
          fixture.publications.prepare(
              actor,
              new DnaSchemaPreparationRequest(
                  "contractor-app",
                  "contractors",
                  "Contractors",
                  "default",
                  null,
                  targetStorage.schema(),
                  targetStorage.checksum(),
                  false));
      fixture.dna.projectModelResource(
          actor,
          new PhysicalModelResourceProjectionCommand(
              contractor.physicalModelId(), "contractors", "app:contractors", 1));
      var contractorSchema =
          new RecordSchemaReference(contractor.physicalModelVersion(), targetStorage.checksum());
      var contractorUser = policy("contractors", "ALL", Map.of(), false);
      String mappedId =
          fixture
              .dna
              .executeRecordTransaction(
                  contractorUser, creates(contractor.physicalModelId(), contractorSchema, 0, 1))
              .results()
              .getFirst()
              .result()
              .recordId();
      var idCount =
          new RecordCountQuery(
              contractor.physicalModelId(),
              contractorSchema,
              new RecordFilter(RecordFilterOperator.IN, "id", List.of(mappedId), List.of()));
      assertEquals(1, fixture.dna.countRecords(contractorUser, idCount));
      var invisible =
          new DnaInvocationContext(
              "tenant-a",
              "staff",
              null,
              "contractors",
              false,
              Set.of("contractors"),
              "DENY",
              Set.of(),
              false,
              List.of(),
              Map.of());
      assertEquals(0, fixture.dna.countRecords(invisible, idCount));

      var replacementTarget =
          new FormRelationTarget("contractor-app", "contractors", "contractor-resource");
      var replacement =
          new FormRelationDefinition(
              "3",
              "contractor-members",
              FormRelationCardinality.MANY_TO_MANY,
              null,
              replacementTarget,
              null,
              null,
              List.of(),
              null,
              null,
              new FormCollectionReplacement("members", Map.of(createdTarget, mappedId)));
      var replacementDocument =
          new ApplicationFormDocument(
              "1",
              List.of(field),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              null,
              List.of(original.retireBy("members"), replacement));
      var replacementBindings =
          List.of(
              oldBinding,
              new FormRelationBinding(
                  "contractor-members",
                  replacementTarget,
                  "contractors-page",
                  "contractors",
                  contractor.physicalModelId()));
      var replacementStorage = storage.compile(replacementDocument, replacementBindings, true);
      assertEquals(afterStorage.checksum(), replacementStorage.checksum());
      var replacementEdges =
          new FormDetailSchemaCoordinator(fixture.publications, storage, fixture.mapper)
              .prepareCollections(
                  actor,
                  "project-app",
                  "projects",
                  root.physicalModelId(),
                  replacementDocument,
                  replacementBindings,
                  false);
      var replacementVersion =
          relationVersion(
              codec,
              replacementDocument,
              replacementStorage,
              changed.physicalModelVersion(),
              replacementBindings,
              replacementEdges);
      var marker =
          codec.decode(replacementVersion.getDocumentJson()).relations().stream()
              .filter(item -> item.relationId().equals("contractor-members"))
              .findFirst()
              .orElseThrow()
              .collectionReplacement();
      var migrate =
          new RecordWritePolicyPublicationCommand(
              root.physicalModelId(),
              "project-app",
              "projects",
              schema,
              2,
              3,
              "migrate-to-contractors",
              replacementVersion.getChecksum(),
              Map.of(),
              30_000,
              Map.of(),
              calculation.compile(replacementVersion, root.physicalModelId(), true),
              command.calculation().collectionsOrEmpty(),
              null,
              new RecordCollectionRebindMigration(
                  marker.sourceRelationId(), "contractor-members", marker.targetRecords()));
      assertEquals(
          DnaRuntimeErrorCode.RECORD_COMMAND_INVALID,
          assertThrows(
                  DnaRuntimeException.class,
                  () -> fixture.dna.publishRecordWritePolicy(actor, migrate))
              .errorCode());
      var migrated = fixture.dna.publishCollectionRebind(actor, migrate);
      for (int attempt = 0; !migrated.published() && attempt < 12; attempt++) {
        migrated = fixture.dna.publishCollectionRebind(actor, migrate);
      }
      assertTrue(migrated.published());
      var permitted = policy("projects", "ALL", Map.of(), "contractors");
      var moved =
          rpc.pageCollection(
              permitted,
              new CollectionRecordPageQuery(
                  root.physicalModelId(),
                  schema,
                  "contractor-members",
                  CollectionRecordPageQuery.Direction.FROM_SOURCE,
                  project,
                  new RecordPageQuery(
                      contractor.physicalModelId(),
                      contractorSchema,
                      0,
                      50,
                      List.of("record_state"),
                      null,
                      List.of()),
                  false));
      assertEquals(List.of(mappedId), moved.records().stream().map(record -> record.id()).toList());
      assertThrows(
          DnaRuntimeException.class,
          () ->
              rpc.pageCollection(
                  user,
                  page(
                      root.physicalModelId(),
                      schema,
                      target.physicalModelId(),
                      targetSchema,
                      project,
                      0,
                      false,
                      false)));
      assertNotNull(
          fixture.dna.getRecord(
              policy("employees", "ALL", Map.of(), false),
              new RecordLookupQuery(
                  target.physicalModelId(), targetSchema, createdTarget, List.of("id"))));
    }
  }

  private static ApplicationFormVersionEntity relationVersion(
      FormDocumentCodec codec,
      ApplicationFormDocument document,
      FormStorageProjectionCompiler.CompiledStorageProjection storage,
      int version,
      List<FormRelationBinding> bindings,
      String collections) {
    var encoded = codec.encode(document);
    var result = new ApplicationFormVersionEntity();
    result.setDocumentJson(encoded.json());
    result.setChecksum(encoded.checksum());
    result.setStorageSchemaJson(storage.json());
    result.setStorageChecksum(storage.checksum());
    result.setPhysicalModelVersion(version);
    result.setRelationBindingsJson(codec.encodeRelationBindings(document, bindings));
    result.setCollectionBindingsJson(collections);
    return result;
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void publishesAndMaintainsTheSameCollectionInBothDirections(boolean postgres) throws Exception {
    try (var fixture = new FormDnaPublicationFixture(postgres)) {
      var storage = new FormStorageProjectionCompiler(fixture.mapper);
      var actor = FormDnaPublicationFixture.actor("tenant-a");
      var plain = new ApplicationFormDocument("1", List.of(), List.of(), List.of(), List.of());
      var employee = storage.compile(plain, List.of(), true);
      var target =
          fixture.publications.prepare(
              actor,
              new DnaSchemaPreparationRequest(
                  "employee-app",
                  "employees",
                  "Employees",
                  "default",
                  null,
                  employee.schema(),
                  employee.checksum(),
                  false));
      assertTrue(target.active());
      var identity = new FormRelationTarget("employee-app", "employees", "employee-resource");
      var relation =
          new FormRelationDefinition(
              "1", "members", FormRelationCardinality.MANY_TO_MANY, null, identity);
      var document =
          new ApplicationFormDocument(
              "1",
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              null,
              List.of(relation));
      var bindings =
          List.of(
              new FormRelationBinding(
                  "members", identity, "employees-page", "employees", target.physicalModelId()));
      var root = storage.compile(document, bindings, true);
      var source =
          fixture.publications.prepare(
              actor,
              new DnaSchemaPreparationRequest(
                  "project-app",
                  "projects",
                  "Projects",
                  "default",
                  null,
                  root.schema(),
                  root.checksum(),
                  false));
      var sourceSchema = new RecordSchemaReference(source.physicalModelVersion(), root.checksum());
      var targetSchema =
          new RecordSchemaReference(target.physicalModelVersion(), employee.checksum());
      assertTrue(source.active());
      var edges =
          new FormDetailSchemaCoordinator(fixture.publications, storage, fixture.mapper)
              .prepareCollections(
                  actor,
                  "project-app",
                  "projects",
                  source.physicalModelId(),
                  document,
                  bindings,
                  false);
      assertNotNull(edges);
      fixture.dna.projectModelResource(
          actor,
          new PhysicalModelResourceProjectionCommand(
              source.physicalModelId(), "projects", "app:projects", 1));
      fixture.dna.projectModelResource(
          actor,
          new PhysicalModelResourceProjectionCommand(
              target.physicalModelId(), "employees", "app:employees", 1));

      var codec = new FormDocumentCodec(fixture.mapper);
      var encoded = codec.encode(document);
      var version = new ApplicationFormVersionEntity();
      version.setDocumentJson(encoded.json());
      version.setChecksum(encoded.checksum());
      version.setStorageSchemaJson(root.json());
      version.setStorageChecksum(root.checksum());
      version.setPhysicalModelVersion(source.physicalModelVersion());
      version.setRelationBindingsJson(codec.encodeRelationBindings(document, bindings));
      version.setCollectionBindingsJson(edges);
      var governance =
          new FormCalculationPublicationProjection(
                  codec, storage, fixture.mapper, Clock.systemUTC())
              .compile(version, source.physicalModelId(), false);
      var publish =
          new RecordWritePolicyPublicationCommand(
              source.physicalModelId(),
              "project-app",
              "projects",
              sourceSchema,
              0,
              1,
              "install-project-members",
              encoded.checksum(),
              Map.of(),
              30_000,
              Map.of(),
              governance);
      var result = fixture.dna.publishRecordWritePolicy(actor, publish);
      for (int attempt = 0; !result.published() && attempt < 12; attempt++) {
        result = fixture.dna.publishRecordWritePolicy(actor, publish);
      }
      assertTrue(result.published());
      var properties = new DnaFormRuntimeRemoteProperties();
      properties.setAllowedCallers(Set.of("app-form-test"));
      var rpc =
          new DnaOwnedRecordServiceImpl(
              new DnaFormRuntimeCallerGuard(properties), fixture.recordServices.owned);
      var user = policy("projects", "ALL", Map.of(), true);
      String project =
          fixture
              .dna
              .executeRecordTransaction(user, creates(source.physicalModelId(), sourceSchema, 1, 1))
              .results()
              .getFirst()
              .result()
              .recordId();
      var employeeUser = policy("employees", "ALL", Map.of(), false);
      var employees = new java.util.ArrayList<String>();
      for (int count : List.of(100, 25)) {
        var created =
            fixture.dna.executeRecordTransaction(
                employeeUser, creates(target.physicalModelId(), targetSchema, 0, count));
        created.results().forEach(item -> employees.add(item.result().recordId()));
      }

      var sourceId = source.physicalModelId();
      var targetId = target.physicalModelId();
      var allCandidates =
          page(sourceId, sourceSchema, targetId, targetSchema, project, 0, false, true);
      assertEquals(125, rpc.pageCollection(user, allCandidates).total());
      var first =
          command(
              sourceId,
              sourceSchema,
              project,
              0,
              "first-100",
              employees.subList(0, 100),
              0,
              -1,
              true);
      var linked = rpc.executeCollection(user, first);
      assertEquals(100, linked.edges().size());
      assertEquals(
          linked, rpc.executeCollection(policy("projects", "ALL", Map.of(), false), first));
      var second =
          command(
              sourceId,
              sourceSchema,
              project,
              1,
              "last-25",
              employees.subList(100, 125),
              0,
              -1,
              true);
      assertEquals(2, rpc.executeCollection(user, second).sourceVersion());
      assertEquals(0, rpc.pageCollection(user, allCandidates).total());
      for (int offset = 0; offset < 3; offset++) {
        var view =
            rpc.pageCollection(
                user,
                page(
                    sourceId, sourceSchema, targetId, targetSchema, project, offset, false, false));
        assertEquals(125, view.total());
        assertEquals(offset == 2 ? 25 : 50, view.records().size());
      }
      String member = employees.getLast();
      var reverse = page(sourceId, sourceSchema, sourceId, sourceSchema, member, 0, true, false);
      assertEquals(project, rpc.pageCollection(user, reverse).records().getFirst().id());

      var hiddenField =
          RecordPermissionModel.key(sourceId)
              + "#"
              + RecordPermissionModel.collectionField("members");
      assertEquals(
          DnaRuntimeErrorCode.RECORD_ACCESS_DENIED,
          assertThrows(
                  DnaRuntimeException.class,
                  () ->
                      rpc.pageCollection(
                          policy("projects", "ALL", Map.of(hiddenField, "MASKED"), true), reverse))
              .errorCode());
      assertEquals(
          0, rpc.pageCollection(policy("projects", "DENY", Map.of(), true), reverse).total());

      var observation =
          new CollectionRecordObservationQuery(
              sourceId, sourceSchema, "members", project, List.of(member));
      assertTrue(rpc.observeCollection(user, observation).edges().getFirst().linked());
      var unlink =
          command(sourceId, sourceSchema, project, 2, "unlink-one", List.of(member), 1, 0, false);
      assertEquals(3, rpc.executeCollection(user, unlink).sourceVersion());
      assertEquals(0, rpc.pageCollection(user, reverse).total());
      assertEquals(
          124,
          rpc.pageCollection(
                  user,
                  page(sourceId, sourceSchema, targetId, targetSchema, project, 2, false, false))
              .total());
      var retained = rpc.observeCollection(user, observation).edges().getFirst();
      assertFalse(retained.linked());
      assertEquals(1, retained.edgeVersion());
      assertEquals(2, retained.targetVersion());
      var late =
          command(sourceId, sourceSchema, project, 3, "late-create", List.of(member), 2, -1, true);
      assertEquals(
          DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT,
          assertThrows(DnaRuntimeException.class, () -> rpc.executeCollection(user, late))
              .errorCode());
      var changed =
          command(sourceId, sourceSchema, project, 3, "unlink-one", List.of(member), 2, 1, true);
      assertEquals(
          DnaRuntimeErrorCode.RECORD_IDEMPOTENCY_CONFLICT,
          assertThrows(DnaRuntimeException.class, () -> rpc.executeCollection(user, changed))
              .errorCode());
      var retryable =
          command(
              sourceId, sourceSchema, project, 3, "concurrent-key", List.of(member), 2, 1, true);
      var ready = new java.util.concurrent.CountDownLatch(2);
      try (var executors = java.util.concurrent.Executors.newFixedThreadPool(2)) {
        java.util.concurrent.Callable<
                org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordResult>
            run =
                () -> {
                  FormDnaPublicationFixture.authenticate("app-form-test");
                  ready.countDown();
                  if (!ready.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                        "Concurrent collection operations did not start");
                  }
                  return rpc.executeCollection(user, retryable);
                };
        var left = executors.submit(run);
        var right = executors.submit(run);
        var committed = left.get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(committed, right.get(30, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(4, committed.sourceVersion());
        assertEquals(2, committed.edges().getFirst().edgeVersion());
      }
      assertEquals(
          125,
          rpc.pageCollection(
                  user,
                  page(sourceId, sourceSchema, targetId, targetSchema, project, 2, false, false))
              .total());
      var installed = governance.collectionsOrEmpty().getFirst();
      fixture.dna.projectModelResource(
          actor,
          new PhysicalModelResourceProjectionCommand(
              targetId, "employee-portal", "app:employee-portal", 1));
      var rebound =
          new RecordCollectionRule(
              "1",
              installed.relationId(),
              installed.edgeModelId(),
              installed.edgeSchema(),
              installed.targetModelId(),
              installed.targetAppId(),
              installed.targetFormId(),
              "employee-portal",
              installed.eligibility(),
              installed.mutableWhen(),
              installed.snapshotLabelFieldId());
      finishPublication(
          fixture,
          actor,
          publication(
              publish,
              1,
              "rebind-member-entry",
              definition(governance, List.of(rebound)),
              List.of()));
      var reboundUser = policy("projects", "ALL", Map.of(), "employee-portal");
      assertEquals(0, rpc.pageCollection(reboundUser, allCandidates).total());
      assertEquals(
          DnaRuntimeErrorCode.RECORD_ACCESS_DENIED,
          assertThrows(DnaRuntimeException.class, () -> rpc.pageCollection(user, reverse))
              .errorCode());
      assertEquals(project, rpc.pageCollection(reboundUser, reverse).records().getFirst().id());

      var withoutCollection = definition(governance, null);
      var missingProof =
          publication(publish, 2, "omit-retirement-plan", withoutCollection, List.of());
      assertEquals(
          DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE,
          assertThrows(
                  DnaRuntimeException.class,
                  () -> fixture.dna.publishRecordWritePolicy(actor, missingProof))
              .errorCode());
      var retire = publication(publish, 2, "retire-members", withoutCollection, List.of(rebound));
      assertEquals(
          DnaRuntimeErrorCode.RECORD_COLLECTION_IN_USE,
          assertThrows(
                  DnaRuntimeException.class,
                  () -> fixture.dna.publishRecordWritePolicy(actor, retire))
              .errorCode());

      for (int offset = 0; offset < employees.size(); offset += 100) {
        var batch = employees.subList(offset, Math.min(offset + 100, employees.size()));
        var versions =
            rpc.observeCollection(
                reboundUser,
                new CollectionRecordObservationQuery(
                    sourceId, sourceSchema, "members", project, batch));
        var removals =
            versions.edges().stream()
                .map(
                    edge ->
                        new CollectionEdgeMutation(
                            edge.targetRecordId(),
                            edge.targetVersion(),
                            edge.edgeVersion(),
                            CollectionEdgeMutation.Mode.UNLINK))
                .toList();
        rpc.executeCollection(
            reboundUser,
            new CollectionRecordCommand(
                "1",
                sourceId,
                sourceSchema,
                "members",
                project,
                versions.sourceVersion(),
                removals,
                "remove-batch-" + offset,
                120_000));
      }
      assertFalse(fixture.dna.publishRecordWritePolicy(actor, retire).published());
      var cancelled = fixture.dna.cancelRecordWritePolicy(actor, retire);
      for (int attempt = 0; cancelled.progress() != null && attempt < 12; attempt++) {
        cancelled = fixture.dna.cancelRecordWritePolicy(actor, retire);
      }
      assertTrue(cancelled.cancelled());
      assertEquals(3, cancelled.version());
      assertEquals(125, rpc.pageCollection(reboundUser, allCandidates).total());
      assertFalse(rpc.observeCollection(reboundUser, observation).edges().getFirst().linked());

      var confirmed =
          publication(publish, 3, "retire-members-again", withoutCollection, List.of(rebound));
      finishPublication(fixture, actor, confirmed);
      assertTrue(fixture.dna.publishRecordWritePolicy(actor, confirmed).published());
      assertThrows(DnaRuntimeException.class, () -> rpc.pageCollection(reboundUser, reverse));
      assertEquals(linked, rpc.findCollectionResult(reboundUser, first));
      var edgeTable =
          new org.springframework.jdbc.core.JdbcTemplate(fixture.control.source)
              .queryForObject(
                  "select table_name from simpoint_dna_physical_bindings where physical_model_id=?"
                      + " and tenant_id=?",
                  String.class,
                  rebound.edgeModelId(),
                  "tenant-a");
      if (edgeTable == null || !edgeTable.matches("[a-z][a-z0-9_]{0,62}")) {
        throw new IllegalStateException("Expected the exact published collection table");
      }
      assertEquals(
          125L,
          new org.springframework.jdbc.core.JdbcTemplate(fixture.target.source)
              .queryForObject(
                  "select count(*) from "
                      + edgeTable
                      + " where tenant_id=? and source_id is null and target_id is null",
                  Long.class,
                  "tenant-a"));
      assertEquals(
          member,
          fixture
              .dna
              .getRecord(
                  employeeUser,
                  new RecordLookupQuery(targetId, targetSchema, member, List.of("record_state")))
              .id());
      var reintroduce =
          publication(
              publish,
              4,
              "reuse-retired-relation",
              definition(governance, List.of(rebound)),
              List.of());
      assertEquals(
          DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE,
          assertThrows(
                  DnaRuntimeException.class,
                  () -> fixture.dna.publishRecordWritePolicy(actor, reintroduce))
              .errorCode());
    }
  }

  private static RecordCalculationDefinition definition(
      RecordCalculationDefinition base, List<RecordCollectionRule> rules) {
    return new RecordCalculationDefinition(
        base.languageVersion(),
        base.formulas(),
        base.details(),
        base.constraints(),
        base.validations(),
        base.stateTransitions(),
        base.writeRules(),
        base.references(),
        rules);
  }

  private static RecordWritePolicyPublicationCommand publication(
      RecordWritePolicyPublicationCommand initial,
      long expected,
      String key,
      RecordCalculationDefinition definition,
      List<RecordCollectionRule> retirement) {
    return new RecordWritePolicyPublicationCommand(
        initial.physicalModelId(),
        initial.appId(),
        initial.formId(),
        initial.schemaReference(),
        expected,
        expected + 1,
        key,
        initial.policyChecksum(),
        Map.of(),
        30_000,
        Map.of(),
        definition,
        retirement);
  }

  private static void finishPublication(
      FormDnaPublicationFixture fixture,
      DnaInvocationContext actor,
      RecordWritePolicyPublicationCommand command) {
    var result = fixture.dna.publishRecordWritePolicy(actor, command);
    for (int attempt = 0; !result.published() && attempt < 12; attempt++) {
      result = fixture.dna.publishRecordWritePolicy(actor, command);
    }
    assertTrue(result.published());
  }

  private static DnaInvocationContext policy(
      String resource, String targetScope, Map<String, String> fields, boolean target) {
    return policy(resource, targetScope, fields, target ? "employees" : null);
  }

  private static DnaInvocationContext policy(
      String resource, String targetScope, Map<String, String> fields, String target) {
    var policy = new DnaResourcePolicy("1", targetScope, Set.of(), false, List.of(), Map.of());
    return new DnaInvocationContext(
        "tenant-a",
        "business-user",
        null,
        resource,
        false,
        target != null
            ? Set.of(resource, resource + ".records.edit", target)
            : Set.of(resource, resource + ".records.edit"),
        "ALL",
        Set.of(),
        false,
        List.of(),
        fields,
        target == null ? Map.of() : Map.of(target, policy));
  }

  private static RecordTransactionCommand creates(
      String model, RecordSchemaReference schema, int revision, int count) {
    var creates =
        IntStream.range(0, count)
            .mapToObj(
                index ->
                    new RecordCreateMutation("record-" + index, Map.of("record_state", "DRAFT")))
            .map(org.simplepoint.plugin.dna.runtime.api.record.RecordMutation.class::cast)
            .toList();
    return new RecordTransactionCommand(
        model, schema, creates, 120_000, "create-" + count, (long) revision);
  }

  private static CollectionRecordCommand command(
      String model,
      RecordSchemaReference schema,
      String project,
      long sourceVersion,
      String key,
      List<String> targets,
      long targetVersion,
      long edgeVersion,
      boolean link) {
    return new CollectionRecordCommand(
        "1",
        model,
        schema,
        "members",
        project,
        sourceVersion,
        targets.stream()
            .map(
                id ->
                    new CollectionEdgeMutation(
                        id,
                        targetVersion,
                        edgeVersion,
                        link
                            ? CollectionEdgeMutation.Mode.LINK
                            : CollectionEdgeMutation.Mode.UNLINK))
            .toList(),
        key,
        120_000);
  }

  private static CollectionRecordPageQuery page(
      String source,
      RecordSchemaReference sourceSchema,
      String result,
      RecordSchemaReference resultSchema,
      String anchor,
      int page,
      boolean reverse,
      boolean candidates) {
    return new CollectionRecordPageQuery(
        source,
        sourceSchema,
        "members",
        reverse
            ? CollectionRecordPageQuery.Direction.FROM_TARGET
            : CollectionRecordPageQuery.Direction.FROM_SOURCE,
        anchor,
        new RecordPageQuery(
            result, resultSchema, page, 50, List.of("record_state"), null, List.of()),
        candidates);
  }
}
