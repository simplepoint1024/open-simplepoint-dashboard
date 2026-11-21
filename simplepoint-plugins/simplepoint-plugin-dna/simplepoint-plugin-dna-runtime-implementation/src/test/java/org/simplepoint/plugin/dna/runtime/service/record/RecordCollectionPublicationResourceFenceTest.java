package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCalculationDefinition;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCollectionRule;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyPublicationCommand;

class RecordCollectionPublicationResourceFenceTest {
  @Test
  void versionThreeRetainsOwnedResourceFencesForFormulaBackfill() {
    var f = new RecordPublicationPatchFixture("database", "forms");
    var base = f.intent.command();
    var edgeSchema = new RecordSchemaReference(1, "c".repeat(64));
    var rule =
        new RecordCollectionRule(
            "1",
            "members",
            "edges",
            edgeSchema,
            "target",
            "target-app",
            "target-form",
            "target-page",
            null,
            null,
            null);
    var definition =
        new RecordCalculationDefinition(
            "1",
            base.calculation().formulas(),
            base.calculation().details(),
            null,
            null,
            null,
            null,
            null,
            List.of(rule));
    var command =
        new RecordWritePolicyPublicationCommand(
            base.physicalModelId(),
            base.appId(),
            base.formId(),
            base.schemaReference(),
            0,
            1,
            "publish-collections",
            base.policyChecksum(),
            Map.of(),
            30_000,
            Map.of(),
            definition);
    var resources =
        Map.of(
            f.parent.physicalModelId(),
            resources(f.parent, 1),
            f.child.physicalModelId(),
            Map.<String, RecordPublicationIntent.Resource>of());
    var intent =
        new RecordPublicationIntent(
            3,
            f.intent.key(),
            command,
            new RecordRequestFingerprint(new ObjectMapper()).of(command),
            f.intent.previousFingerprint(),
            f.intent.actorId(),
            f.intent.evaluatedAt(),
            Map.of(
                f.parent.physicalModelId(),
                f.binding(f.parent, 0),
                f.child.physicalModelId(),
                f.binding(f.child, 0)),
            null,
            resources,
            Map.of(),
            Map.of(
                "members",
                new RecordPublicationIntent.Binding(
                    edgeSchema,
                    edgeSchema,
                    new RuntimeMetadataTarget("source", "database", "forms", "edges"),
                    0)));
    var child = new RecordPublicationPatchCompiler.Resolved(f.child, 0, Map.of());
    f.compiler.validate(
        intent,
        Map.of(
            f.parent.physicalModelId(),
            new RecordPublicationPatchCompiler.Resolved(f.parent, 0, resources(f.parent, 1)),
            f.child.physicalModelId(),
            child));
    for (var changed :
        List.of(resources(f.parent, 2), Map.<String, RecordPublicationIntent.Resource>of())) {
      assertThatThrownBy(
              () ->
                  f.compiler.validate(
                      intent,
                      Map.of(
                          f.parent.physicalModelId(),
                          new RecordPublicationPatchCompiler.Resolved(f.parent, 0, changed),
                          f.child.physicalModelId(),
                          child)))
          .isInstanceOf(DnaRuntimeException.class);
    }
  }

  private static Map<String, RecordPublicationIntent.Resource> resources(
      RuntimeRecordModel model, long generation) {
    return Map.of(
        "page",
        new RecordPublicationIntent.Resource(
            "binding",
            new RecordPublicationIntent.Projection(
                "projection", "app:page", generation, model.physicalModelId())));
  }
}
