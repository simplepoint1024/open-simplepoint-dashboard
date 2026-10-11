package org.simplepoint.plugin.dna.runtime.service.impl;

import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationStepStatus;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaMigrationStepView;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPlanView;
import org.simplepoint.plugin.dna.runtime.api.service.SchemaPlanService;
import org.simplepoint.plugin.dna.runtime.service.impl.SchemaPublicationStateService.PlanningWork;
import org.simplepoint.plugin.dna.runtime.service.schema.InitialSchemaPlan;
import org.simplepoint.plugin.dna.runtime.service.schema.JdbcPhysicalSchemaBackend;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.springframework.stereotype.Service;

/** Read-only schema planner using the same backend and dialect as publication. */
@Service
public class SchemaPlanServiceImpl implements SchemaPlanService {

  private final RuntimeRequestContext requestContext;
  private final SchemaPublicationStateService stateService;
  private final JdbcPhysicalSchemaBackend backend;

  /** Creates the plan service. */
  public SchemaPlanServiceImpl(
      final RuntimeRequestContext requestContext,
      final SchemaPublicationStateService stateService,
      final JdbcPhysicalSchemaBackend backend
  ) {
    this.requestContext = requestContext;
    this.stateService = stateService;
    this.backend = backend;
  }

  @Override
  public SchemaPlanView plan(final String physicalModelId, final int targetVersion) {
    RuntimeActor actor = requestContext.requireActor();
    PlanningWork work = stateService.planningWork(actor, physicalModelId, targetVersion);
    InitialSchemaPlan plan = backend.plan(
        work.target(),
        work.displayName(),
        work.currentSchema(),
        work.schema()
    );
    List<SchemaMigrationStepView> steps = plan.steps().stream()
        .map(step -> new SchemaMigrationStepView(
            step.sequence(),
            step.operation(),
            step.logicalObject(),
            step.risk(),
            SchemaMigrationStepStatus.PENDING,
            0,
            null,
            null,
            null
        ))
        .toList();
    return new SchemaPlanView(
        work.physicalModelId(),
        work.targetVersion(),
        plan.dialectId(),
        plan.actualSnapshot().checksum(),
        plan.targetSnapshot().checksum(),
        plan.risk(),
        steps
    );
  }
}
