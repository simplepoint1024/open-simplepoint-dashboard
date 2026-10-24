package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.LinkedHashMap;
import java.util.Map;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCompletenessView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

/** Existing historical completeness/invariant checks run against the fully applied candidate before activation. */
final class RecordPublicationChecks {
  private RecordPublicationChecks() {}

  static void verify(final RecordPublicationIntent intent, final RuntimeRecordModel root,
      final Map<RecordSchemaReference, RuntimeRecordModel> historical, final RecordSqlCompiler sql,
      final PhysicalSchemaCodec schemas, final RecordPublicationSession session) {
    var actor = new RuntimeActor(intent.key().tenantId(), intent.actorId());
    var violations = new LinkedHashMap<String, RecordCompletenessView>();
    var values = new LinkedHashMap<String, Long>();
    intent.command().checks().forEach((key, query) -> {
      var model = model(root, query.schemaReference(), historical, schemas);
      var access = RecordManagementAccess.forOwner(actor, model, intent.command().appId(), intent.command().formId());
      var result = RecordCompletenessResult.read(query, session.aggregate(sql.completeness(model, access, query)));
      if (result.emptyCounts().values().stream().anyMatch(count -> count > 0)) {
        violations.put(key, result);
      }
    });
    intent.command().valueChecks().forEach((key, query) -> {
      var model = model(root, query.schemaReference(), historical, schemas);
      var access = RecordManagementAccess.forOwner(actor, model, intent.command().appId(), intent.command().formId());
      long count = RecordViolationResult.read(session.aggregate(sql.violations(model, access, query)));
      if (count > 0) {
        values.put(key, count);
      }
    });
    if (!violations.isEmpty() || !values.isEmpty()) {
      throw new Rejected(violations, values);
    }
  }

  private static RuntimeRecordModel model(final RuntimeRecordModel root, final RecordSchemaReference reference,
      final Map<RecordSchemaReference, RuntimeRecordModel> historical, final PhysicalSchemaCodec schemas) {
    var model = historical.get(reference);
    if (model == null || !root.physicalModelId().equals(model.physicalModelId()) || !root.target().equals(model.target())
        || !root.appId().equals(model.appId()) || !root.formId().equals(model.formId())
        || !root.activeSchema().equals(model.activeSchema()) || root.activeVersion() != model.activeVersion()
        || reference.version() != model.requestedVersion() || !reference.checksum().equals(schemas.encode(model.requestedSchema()).checksum())) {
      throw new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE, "Publication inspection schema is unavailable or changed");
    }
    return model;
  }

  /** Only aggregate violation counts, never record values, may be projected to the owning publication. */
  static final class Rejected extends DnaRuntimeException {
    private final Map<String, RecordCompletenessView> violations;
    private final Map<String, Long> values;
    private final org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyProgress progress;

    Rejected(final Map<String, RecordCompletenessView> violations, final Map<String, Long> values) {
      this(violations, values, null);
    }

    Rejected(final Map<String, RecordCompletenessView> violations, final Map<String, Long> values,
        final org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyProgress progress) {
      super(DnaRuntimeErrorCode.RECORD_WRITE_POLICY_PRECHECK_FAILED, "Applied calculation values do not satisfy publication checks");
      this.progress = progress;
      this.violations = Map.copyOf(violations);
      this.values = Map.copyOf(values);
    }

    Map<String, RecordCompletenessView> violations() {
      return violations;
    }

    org.simplepoint.plugin.dna.runtime.api.record.RecordWritePolicyProgress progress() {
      return progress;
    }

    Map<String, Long> valueViolations() {
      return values;
    }
  }
}
