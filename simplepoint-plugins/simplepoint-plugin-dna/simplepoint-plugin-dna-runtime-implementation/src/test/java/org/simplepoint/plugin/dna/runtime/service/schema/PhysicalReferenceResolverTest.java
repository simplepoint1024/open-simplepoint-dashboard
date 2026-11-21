package org.simplepoint.plugin.dna.runtime.service.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.api.model.RuntimeMetadataTarget;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalReferenceField;
import org.simplepoint.plugin.dna.runtime.api.schema.OwnedModelRelation;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelBindingEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelVersionEntity;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelBindingRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelVersionRepository;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;

class PhysicalReferenceResolverTest {
  private final PhysicalModelRepository models = mock(PhysicalModelRepository.class);
  private final PhysicalModelBindingRepository bindings =
      mock(PhysicalModelBindingRepository.class);
  private final PhysicalModelVersionRepository versions =
      mock(PhysicalModelVersionRepository.class);
  private final PhysicalSchemaCodec schemas = new PhysicalSchemaCodec(new ObjectMapper());
  private final PhysicalReferenceResolver resolver =
      new PhysicalReferenceResolver(models, bindings, versions, schemas);
  private final PhysicalSchemaTarget source =
      new PhysicalSchemaTarget(
          "trades", new RuntimeMetadataTarget("source", "db", "public", "trades"));
  private final PhysicalSchemaDefinition schema =
      new PhysicalSchemaDefinition(
          DnaRuntimeApiVersion.CURRENT,
          List.of(
              new LogicalFieldDefinition(
                  "customer",
                  "customer_id",
                  "客户",
                  LogicalFieldType.UUID,
                  true,
                  null,
                  null,
                  null,
                  null)),
          List.of(),
          null,
          true,
          List.of(
              new LogicalReferenceDefinition("trade-customer", "customer", "customers", false)));

  @Test
  void resolvesOnlyTheActiveVersionInTheCurrentTenant() {
    var target = prepare();
    var result = resolver.resolve("tenant-a", source, schema);
    assertThat(result.referenceTargets()).containsOnlyKeys("customers");
    assertThat(result.referenceTargets().get("customers").tableName())
        .isEqualTo(target.binding().getTableName());
    verify(versions).findActiveVersion("tenant-a", "customers", 2);
    assertThatThrownBy(() -> resolver.resolve("tenant-b", source, schema))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void ownedSourceRequiresMatchingAdoptedTargetAndRetainsItsExpectation() {
    var target = prepare();
    String namespace = java.util.UUID.randomUUID().toString();
    var owned = source.withOwnership(namespace, "tenant-a");
    assertThatThrownBy(() -> resolver.resolve("tenant-a", owned, schema))
        .isInstanceOf(DnaRuntimeException.class);
    target.binding().setPhysicalNamespaceId(java.util.UUID.randomUUID().toString());
    assertThatThrownBy(() -> resolver.resolve("tenant-a", owned, schema))
        .isInstanceOf(DnaRuntimeException.class);
    target.binding().setPhysicalNamespaceId(namespace);
    var resolved = resolver.resolve("tenant-a", owned, schema);
    assertThat(resolved.ownership()).isEqualTo(owned.ownership());
    assertThat(resolved.referenceTargets()).containsOnlyKeys("customers");
  }

  @Test
  void rejectsUnpublishedTargetsAndDifferentRegisteredStores() {
    var target = prepare();
    target.model().setStatus(PhysicalModelStatus.DRAFT);
    assertThatThrownBy(() -> resolver.resolve("tenant-a", source, schema))
        .isInstanceOf(DnaRuntimeException.class);
    target.model().setStatus(PhysicalModelStatus.ACTIVE);
    target.binding().setDataSourceId("different-registration");
    assertThatThrownBy(() -> resolver.resolve("tenant-a", source, schema))
        .isInstanceOf(DnaRuntimeException.class);
    target.binding().setDataSourceId("source");
    target.binding().setSchemaName("other");
    assertThatThrownBy(() -> resolver.resolve("tenant-a", source, schema))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void rejectsOwnedDetailTargetsAndTargetsWithoutTenantIdentity() {
    var target = prepare();
    target
        .version()
        .setSchemaJson(
            schemas
                .encode(
                    new PhysicalSchemaDefinition(
                        DnaRuntimeApiVersion.CURRENT, List.of(), List.of()))
                .json());
    assertThatThrownBy(() -> resolver.resolve("tenant-a", source, schema))
        .isInstanceOf(DnaRuntimeException.class);
    var field =
        new LogicalFieldDefinition(
            "owner", "owner_id", "所属", LogicalFieldType.UUID, false, null, null, null, null);
    target
        .version()
        .setSchemaJson(
            schemas
                .encode(
                    new PhysicalSchemaDefinition(
                        DnaRuntimeApiVersion.CURRENT,
                        List.of(field),
                        List.of(),
                        new OwnedModelRelation("parent", "owner"),
                        true))
                .json());
    assertThatThrownBy(() -> resolver.resolve("tenant-a", source, schema))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void selfReferenceUsesThePlannedIndependentModelAndItsIdentityKey() {
    var self = new PhysicalSchemaTarget("customers", source.metadataTarget());
    assertThat(resolver.resolve("tenant-a", self, schema).referenceTargets())
        .containsEntry("customers", source.metadataTarget());
    var missingKey =
        new PhysicalSchemaDefinition(
            DnaRuntimeApiVersion.CURRENT,
            schema.fields(),
            List.of(),
            null,
            null,
            schema.references());
    assertThatThrownBy(() -> resolver.resolve("tenant-a", self, missingKey))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void validatesEveryRelationToTheSameTargetIncludingRetainedAndSelfReferences() {
    var target = prepare();
    var name =
        new LogicalFieldDefinition(
            "name", "renamed_label", "Name", LogicalFieldType.STRING, true, 100, null, null, null);
    target
        .version()
        .setSchemaJson(
            schemas
                .encode(
                    new PhysicalSchemaDefinition(
                        DnaRuntimeApiVersion.CURRENT, List.of(name), List.of(), null, true))
                .json());
    var first = schema.references().getFirst();
    var otherField =
        new LogicalFieldDefinition(
            "other", "other_id", "Other", LogicalFieldType.UUID, true, null, null, null, null);
    for (var dependency :
        List.of(
            new LogicalReferenceField("missing", LogicalFieldType.STRING),
            new LogicalReferenceField("name", LogicalFieldType.BOOLEAN))) {
      var invalid =
          new PhysicalSchemaDefinition(
              DnaRuntimeApiVersion.CURRENT,
              List.of(schema.fields().getFirst(), otherField),
              List.of(),
              null,
              true,
              List.of(
                  first,
                  new LogicalReferenceDefinition(
                      "z-second", "other", "customers", false, List.of(dependency))));
      assertThatThrownBy(() -> resolver.resolve("tenant-a", source, invalid))
          .isInstanceOf(DnaRuntimeException.class);
      assertThatThrownBy(() -> resolver.resolveStored("tenant-a", source, invalid))
          .isInstanceOf(DnaRuntimeException.class);
    }
    var valid =
        new PhysicalSchemaDefinition(
            DnaRuntimeApiVersion.CURRENT,
            schema.fields(),
            List.of(),
            null,
            true,
            List.of(
                new LogicalReferenceDefinition(
                    first.relationId(),
                    first.fieldId(),
                    first.targetModelId(),
                    false,
                    List.of(new LogicalReferenceField("name", LogicalFieldType.STRING)))));
    assertThat(resolver.resolve("tenant-a", source, valid).referenceTargets())
        .containsOnlyKeys("customers");
    assertThatThrownBy(
            () ->
                resolver.resolve(
                    "tenant-a",
                    new PhysicalSchemaTarget("customers", source.metadataTarget()),
                    valid))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void retainedStorageResolutionDoesNotGrantPermissionToPublishNewLink() {
    var target = prepare();
    target.model().setStatus(PhysicalModelStatus.RETIRED);
    assertThat(resolver.resolveStored("tenant-a", source, schema).referenceTargets())
        .containsOnlyKeys("customers");
    assertThatThrownBy(() -> resolver.resolve("tenant-a", source, schema))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(() -> resolver.resolveStored("tenant-b", source, schema))
        .isInstanceOf(DnaRuntimeException.class);
    target.model().setActiveVersion(null);
    assertThatThrownBy(() -> resolver.resolveStored("tenant-a", source, schema))
        .isInstanceOf(DnaRuntimeException.class);
  }

  private Target prepare() {
    var model = new PhysicalModelEntity();
    model.setId("customers");
    model.setTenantId("tenant-a");
    model.setStatus(PhysicalModelStatus.ACTIVE);
    model.setActiveVersion(2);
    model.setLatestVersion(3);
    var binding = new PhysicalModelBindingEntity();
    binding.setDataSourceId("source");
    binding.setCatalogName("db");
    binding.setSchemaName("public");
    binding.setTableName("customers");
    var version = new PhysicalModelVersionEntity();
    version.setSchemaJson(
        schemas
            .encode(
                new PhysicalSchemaDefinition(
                    DnaRuntimeApiVersion.CURRENT, List.of(), List.of(), null, true))
            .json());
    when(models.findActiveById("tenant-a", "customers")).thenReturn(Optional.of(model));
    when(bindings.findActiveByModel("tenant-a", "customers")).thenReturn(Optional.of(binding));
    when(versions.findActiveVersion("tenant-a", "customers", 2)).thenReturn(Optional.of(version));
    return new Target(model, binding, version);
  }

  private record Target(
      PhysicalModelEntity model,
      PhysicalModelBindingEntity binding,
      PhysicalModelVersionEntity version) {}
}
