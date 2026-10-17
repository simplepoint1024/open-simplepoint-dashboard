package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

/** Owner-only scans cover the whole tenant/model, independently from a page reader's scope. */
final class RecordManagementAccess {

  private RecordManagementAccess() {
  }

  static void requireAuthority() {
    var authorization = AuthorizationContextHolder.getContext();
    if (authorization == null || !Boolean.TRUE.equals(authorization.getIsAdministrator())) {
      throw new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_ACCESS_DENIED,
          "Record management requires owner management authority");
    }
  }

  static RecordAccessContext forOwner(final RuntimeActor actor, final RuntimeRecordModel model,
      final String appId, final String formId) {
    if (!model.appId().equals(appId) || !model.formId().equals(formId)) {
      throw new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_ACCESS_DENIED,
          "Record management owner does not match the physical model");
    }
    Set<String> fields = model.requestedSchema().fields().stream()
        .map(LogicalFieldDefinition::code).collect(Collectors.toUnmodifiableSet());
    return new RecordAccessContext(actor, ResourcePolicyContext.get(), fields, Set.of(), Set.of(),
        new DataScopeCondition("ALL", "created_org_dept_id", "created_by", null, Set.of()), null);
  }
}
