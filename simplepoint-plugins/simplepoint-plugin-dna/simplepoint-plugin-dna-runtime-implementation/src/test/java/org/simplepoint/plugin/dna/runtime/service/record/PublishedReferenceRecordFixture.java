package org.simplepoint.plugin.dna.runtime.service.record;

import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceProjectionCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.configuration.DnaRuntimeExecutionProperties;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelBindingRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelResourceBindingRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelVersionRepository;
import org.simplepoint.plugin.dna.runtime.service.record.RecordMaintenanceControlDatabaseTest.ControlFixture;
import org.simplepoint.plugin.dna.runtime.service.record.RecordPublicationPatchDatabaseTest.Database;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalReferenceResolver;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

/** Ordinary record commands over real control repositories, lifecycle locks and Calcite. */
final class PublishedReferenceRecordFixture {
  private static final String RESOURCE = "customer-page";
  private final RecordTransactionServiceImpl commands;
  private final PhysicalModelView model;

  PublishedReferenceRecordFixture(Database db, ControlFixture control, PhysicalModelView model) {
    this.model = model;
    control
        .resource()
        .project(
            new PhysicalModelResourceProjectionCommand(
                model.id(), RESOURCE, "app:customer-page", 1));
    var repositories = new JpaRepositoryFactory(control.em());
    var models = repositories.getRepository(PhysicalModelRepository.class);
    var bindings = repositories.getRepository(PhysicalModelBindingRepository.class);
    var versions = repositories.getRepository(PhysicalModelVersionRepository.class);
    var codec = db.fixture().codec;
    var resolver =
        new RuntimeRecordModelResolver(
            models,
            bindings,
            versions,
            codec,
            new PhysicalReferenceResolver(models, bindings, versions, codec));
    var resources =
        new RecordResourceFence(
            control.em(), control.transactions().getTransactionManager(), control.relations());
    var access =
        new RuntimeRecordAccessPolicy(
            repositories.getRepository(PhysicalModelResourceBindingRepository.class));
    access.setResourceFence(resources);
    var sql = db.fixture().sql;
    var calculations = new RecordCalculationWriteSupport(resolver, codec, sql);
    var context = new RuntimeRequestContext();
    db.backend().setResourceFence(resources);
    db.backend()
        .setReferenceAdmission(
            new RecordReferenceAdmission(
                context,
                resolver,
                access,
                new RecordCalculationAccess(calculations, access),
                sql,
                codec),
            sql);
    commands =
        new RecordTransactionServiceImpl(
            context,
            resolver,
            access,
            sql,
            db.backend(),
            mock(RecordAuditSupport.class),
            new RecordAdmissionController(new DnaRuntimeExecutionProperties()),
            mock(RecordExecutionObserver.class),
            new RecordRequestFingerprint(new ObjectMapper()),
            calculations);
  }

  String create() {
    return execute(new RecordCreateMutation("create", Map.of()));
  }

  void delete(String id) {
    execute(new RecordDeleteMutation("delete", id, 0));
  }

  private String execute(RecordMutation mutation) {
    ResourcePolicyContext.set(RESOURCE);
    try {
      return commands
          .execute(
              new RecordTransactionCommand(
                  model.id(),
                  new RecordSchemaReference(
                      model.latestSchema().version(), model.latestSchema().checksum()),
                  List.of(mutation),
                  30_000))
          .results()
          .getFirst()
          .result()
          .recordId();
    } finally {
      ResourcePolicyContext.clear();
    }
  }
}
