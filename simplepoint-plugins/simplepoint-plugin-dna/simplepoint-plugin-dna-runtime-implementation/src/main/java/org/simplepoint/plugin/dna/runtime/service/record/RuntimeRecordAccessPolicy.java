package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.ResourceDataScope;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelResourceBindingRepository;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.springframework.stereotype.Component;

/** Enforces server-selected page, dynamic field, and row-scope policy for records. */
@Component
public class RuntimeRecordAccessPolicy {

  static final Set<String> SYSTEM_FIELDS = Set.of(
      "id",
      "tenant_id",
      "record_version",
      "created_by",
      "created_org_dept_id",
      "created_at",
      "updated_by",
      "updated_at",
      "deleted_at"
  );

  private static final String ORGANIZATION_ATTRIBUTE = "X-Org-Dept-Id";

  private final PhysicalModelResourceBindingRepository resourceBindingRepository;

  /** Creates the access policy from the durable model-resource projection. */
  public RuntimeRecordAccessPolicy(
      final PhysicalModelResourceBindingRepository resourceBindingRepository
  ) {
    this.resourceBindingRepository = resourceBindingRepository;
  }

  /** Resolves effective permissions and rejects unbound or ambiguous page contexts. */
  RecordAccessContext authorize(
      final RuntimeActor actor,
      final RuntimeRecordModel model
  ) {
    AuthorizationContext authorization = AuthorizationContextHolder.getContext();
    String resourceCode = normalize(ResourcePolicyContext.get());
    if (authorization == null || resourceCode == null) {
      throw denied("A server-selected page resource is required for record access");
    }
    Collection<String> resources = authorization.getResources() == null
        ? List.of() : authorization.getResources();
    if (!Boolean.TRUE.equals(authorization.getIsAdministrator())
        && !resources.contains(resourceCode)) {
      throw denied("The current actor has no access to the active page resource");
    }
    if (resourceBindingRepository.findActive(
        actor.tenantId(),
        model.physicalModelId(),
        resourceCode
    ).isEmpty()) {
      throw denied("The active page resource is not bound to the physical model");
    }

    Set<String> modelFields = new LinkedHashSet<>(Set.of(
        "id",
        "record_version",
        "created_by",
        "created_at",
        "updated_by",
        "updated_at"
    ));
    model.schema().fields().forEach(field -> modelFields.add(field.code()));
    Set<String> readable = new LinkedHashSet<>();
    Set<String> writable = new LinkedHashSet<>();
    Set<String> masked = new LinkedHashSet<>();
    Map<String, String> permissions = authorization.activeFieldPermissions();
    String permissionModel = RecordPermissionModel.key(model.physicalModelId());
    for (String field : modelFields) {
      String access = permissions == null ? null : permissions.get(permissionModel + '#' + field);
      if (access == null || Set.of("EDITABLE", "VISIBLE", "MASKED").contains(access)) {
        readable.add(field);
      }
      if ("MASKED".equals(access)) {
        masked.add(field);
      }
      if (!SYSTEM_FIELDS.contains(field) && (access == null || "EDITABLE".equals(access))) {
        writable.add(field);
      }
    }
    return new RecordAccessContext(
        actor,
        resourceCode,
        readable,
        writable,
        masked,
        rowScope(authorization),
        normalize(authorization.getAttribute(ORGANIZATION_ATTRIBUTE))
    );
  }

  private static DataScopeCondition rowScope(final AuthorizationContext context) {
    ResourceDataScope pageScope = context.activeDataScope();
    if (pageScope != null) {
      return pageScope.condition("created_org_dept_id", "created_by", context);
    }
    if (Boolean.TRUE.equals(context.getIsAdministrator())) {
      return new DataScopeCondition("ALL", "created_org_dept_id", "created_by", null, Set.of());
    }
    String type = normalize(context.getDataScopeType());
    return new DataScopeCondition(
        type == null ? "DENY" : type,
        "created_org_dept_id",
        "created_by",
        context.getUserId(),
        context.getDeptIds() == null ? Set.of() : context.getDeptIds(),
        Boolean.TRUE.equals(context.getDataScopeIncludeSelf())
    );
  }

  private static String normalize(final String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private static DnaRuntimeException denied(final String message) {
    return new DnaRuntimeException(DnaRuntimeErrorCode.RECORD_ACCESS_DENIED, message);
  }
}
