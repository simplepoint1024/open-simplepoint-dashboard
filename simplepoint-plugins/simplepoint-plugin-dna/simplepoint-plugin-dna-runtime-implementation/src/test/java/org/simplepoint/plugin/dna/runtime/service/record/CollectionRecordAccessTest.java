package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.ResourceDataScope;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.schema.CollectionModelRelation;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalIndexDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelResourceBindingRepository;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

class CollectionRecordAccessTest {
  @AfterEach
  void clear() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    ResourcePolicyContext.clear();
  }

  @Test
  void directAndReferenceAccessCannotTreatManagedEdgesAsBusinessRecordsEvenForAdministrators() {
    var bindings = mock(PhysicalModelResourceBindingRepository.class);
    final var access = new RuntimeRecordAccessPolicy(bindings);
    final var actor = new RuntimeActor("tenant", "actor");
    var context = new AuthorizationContext();
    context.setUserId("actor");
    context.setIsAdministrator(true);
    context.setResources(Set.of("projects"));
    context.setResourceDataScopes(
        Map.of("projects", new ResourceDataScope("ALL", Set.of(), false)));
    context.setResourceFieldPermissions(Map.of("projects", Map.of()));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    ResourcePolicyContext.set("projects");
    var model = new RuntimeRecordModel("edges", "app", "members", 1, schema(true), null);
    for (Runnable request :
        List.<Runnable>of(
            () -> access.authorize(actor, model),
            () -> access.authorizeReference(actor, model, "projects"))) {
      assertThatThrownBy(request::run)
          .isInstanceOfSatisfying(
              DnaRuntimeException.class,
              failure ->
                  assertThat(failure.errorCode())
                      .isEqualTo(DnaRuntimeErrorCode.RECORD_ACCESS_DENIED))
          .hasMessageContaining("Collection edges");
    }
    verifyNoInteractions(bindings);
  }

  @Test
  void oldBusinessSchemasCannotBypassCollectionOwnershipAndCollectionSchemasCannotLoseIt() {
    for (boolean incoming : List.of(false, true)) {
      assertThatThrownBy(
              () ->
                  RecordSchemaCompatibility.requireCompatible(schema(incoming), schema(!incoming)))
          .isInstanceOfSatisfying(
              DnaRuntimeException.class,
              failure ->
                  assertThat(failure.errorCode())
                      .isEqualTo(DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE));
    }
  }

  private static PhysicalSchemaDefinition schema(boolean managed) {
    return new PhysicalSchemaDefinition(
        "v1",
        List.of(field("left"), field("right")),
        List.of(new LogicalIndexDefinition("edge_pair", List.of("left", "right"), true)),
        null,
        true,
        List.of(
            new LogicalReferenceDefinition("source", "left", "projects", false),
            new LogicalReferenceDefinition("target", "right", "employees", false)),
        managed ? new CollectionModelRelation("members", "source", "target") : null);
  }

  private static LogicalFieldDefinition field(String id) {
    return new LogicalFieldDefinition(
        id, id, id, LogicalFieldType.UUID, true, null, null, null, null);
  }
}
