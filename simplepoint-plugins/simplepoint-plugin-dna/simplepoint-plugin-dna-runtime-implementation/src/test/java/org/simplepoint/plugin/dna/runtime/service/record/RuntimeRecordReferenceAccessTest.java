package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.*;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.error.*;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.record.*;
import org.simplepoint.plugin.dna.runtime.api.schema.*;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelResourceBindingEntity;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelResourceBindingRepository;
import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;

class RuntimeRecordReferenceAccessTest {
  private static final RuntimeActor ACTOR = new RuntimeActor("tenant", "actor");
  private final PhysicalModelResourceBindingRepository bindings =
      mock(PhysicalModelResourceBindingRepository.class);
  private final RuntimeRecordAccessPolicy policy = new RuntimeRecordAccessPolicy(bindings);
  private final RuntimeRecordModel target = model();

  @AfterEach
  void clear() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    ResourcePolicyContext.clear();
  }

  @Test
  void targetUsesItsOwnRowAndFieldPolicyWithoutChangingSourceContext() {
    var targetFields =
        Map.of(key("secret"), "HIDDEN", key("name"), "MASKED", key("phone"), "VISIBLE");
    context(false, Set.of("source", "target"), targetFields, true);
    when(bindings.findActive("tenant", "customer", "target"))
        .thenReturn(Optional.of(new PhysicalModelResourceBindingEntity()));
    var generation = mock(RecordResourceFence.class);
    when(generation.generation("tenant", "customer", "target")).thenReturn(9L);
    policy.setResourceFence(generation);
    var access = policy.authorizeReference(ACTOR, target, "target");
    assertThat(access.rowScope().getScopeType()).isEqualTo("CUSTOM");
    assertThat(access.rowScope().getDeptIds()).containsExactly("sales");
    assertThat(access.readableFields()).contains("name", "phone").doesNotContain("secret");
    assertThat(access.maskedFields()).containsExactly("name");
    assertThat(access.writableFields()).doesNotContain("name", "phone", "secret");
    assertThat(access.resourceGeneration()).isEqualTo(9);
    assertThat(ResourcePolicyContext.get()).isEqualTo("source");
    assertThat(AuthorizationContextHolder.getContext().activeDataScope().type()).isEqualTo("ALL");
    var sql = new RecordSqlCompiler(new StandardLogicalValueConverter());
    var reference = new RecordSchemaReference(1, "a".repeat(64));
    var page =
        sql.page(
            target,
            access,
            new RecordPageQuery("customer", reference, 0, 50, List.of("phone"), null, List.of()));
    assertThat(page.parameters()).contains("tenant", "sales");
    var hiddenFilter =
        new RecordFilter(RecordFilterOperator.EQUALS, "secret", List.of("guess"), List.of());
    assertThatThrownBy(() -> sql.count(target, access, hiddenFilter))
        .isInstanceOf(DnaRuntimeException.class);
    var maskedFilter =
        new RecordFilter(RecordFilterOperator.EQUALS, "name", List.of("guess"), List.of());
    assertThatThrownBy(() -> sql.count(target, access, maskedFilter))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void absentTargetPolicyNeverFallsBackToSourceOrAdministratorAuthority() {
    for (boolean administrator : List.of(false, true)) {
      context(administrator, Set.of("source", "target"), Map.of(), false);
      assertThatThrownBy(() -> policy.authorizeReference(ACTOR, target, "target"))
          .isInstanceOfSatisfying(
              DnaRuntimeException.class,
              failure ->
                  assertThat(failure.errorCode())
                      .isEqualTo(DnaRuntimeErrorCode.RECORD_ACCESS_DENIED));
      assertThat(ResourcePolicyContext.get()).isEqualTo("source");
    }
    verifyNoInteractions(bindings);
  }

  @Test
  void missingGrantOrDifferentModelBindingCannotBorrowAnAlternativeEntry() {
    context(false, Set.of("source"), Map.of(), true);
    assertThatThrownBy(() -> policy.authorizeReference(ACTOR, target, "target"))
        .isInstanceOf(DnaRuntimeException.class);
    verifyNoInteractions(bindings);
    context(false, Set.of("source", "target", "alternative"), Map.of(), true);
    when(bindings.findActive("tenant", "customer", "alternative"))
        .thenReturn(Optional.of(new PhysicalModelResourceBindingEntity()));
    assertThatThrownBy(() -> policy.authorizeReference(ACTOR, target, "target"))
        .isInstanceOf(DnaRuntimeException.class);
    verify(bindings).findActive("tenant", "customer", "target");
    verify(bindings, never()).findActive("tenant", "customer", "alternative");
  }

  @Test
  void ownedFieldsFollowTheirAuthorizedParentResourceRatherThanAmbientPage() {
    context(false, Set.of("source", "target"), Map.of(key("secret"), "HIDDEN"), true);
    var parent =
        new RecordAccessContext(ACTOR, "target", Set.of(), Set.of(), Set.of(), null, null, 8);
    var access = policy.owned(ACTOR, target, parent);
    assertThat(access.readableFields()).doesNotContain("secret");
    assertThat(access.resourceCode()).isEqualTo("target");
    assertThat(access.resourceGeneration()).isEqualTo(8);
  }

  private void context(
      boolean administrator, Set<String> resources, Map<String, String> fields, boolean explicit) {
    var auth = new AuthorizationContext();
    auth.setUserId("actor");
    auth.setIsAdministrator(administrator);
    auth.setResources(resources);
    auth.setAttributes(Map.of("X-Tenant-Id", "tenant"));
    auth.setDataScopeType("ALL");
    var data = new java.util.HashMap<String, ResourceDataScope>();
    var fieldPolicies = new java.util.HashMap<String, Map<String, String>>();
    data.put("source", new ResourceDataScope("ALL", Set.of(), false));
    fieldPolicies.put("source", Map.of());
    if (explicit) {
      data.put("target", new ResourceDataScope("CUSTOM", Set.of("sales"), false));
      fieldPolicies.put("target", fields);
    }
    auth.setResourceDataScopes(data);
    auth.setResourceFieldPermissions(fieldPolicies);
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, auth);
    ResourcePolicyContext.set("source");
  }

  private static String key(String field) {
    return RecordPermissionModel.key("customer") + "#" + RecordPermissionModel.field(field);
  }

  private static RuntimeRecordModel model() {
    var schema =
        new PhysicalSchemaDefinition(
            DnaRuntimeApiVersion.CURRENT,
            List.of("name", "phone", "secret").stream()
                .map(
                    id ->
                        new LogicalFieldDefinition(
                            id, id, id, LogicalFieldType.STRING, true, 80, null, null, null))
                .toList(),
            List.of());
    return new RuntimeRecordModel(
        "customer",
        "app",
        "form",
        1,
        schema,
        new PhysicalSchemaTarget(
            "customer", new RuntimeMetadataTarget("store", "", "forms", "customers")));
  }
}
