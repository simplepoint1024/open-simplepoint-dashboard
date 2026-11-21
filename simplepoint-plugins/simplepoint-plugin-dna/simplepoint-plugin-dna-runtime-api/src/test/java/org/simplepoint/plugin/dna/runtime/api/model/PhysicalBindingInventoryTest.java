package org.simplepoint.plugin.dna.runtime.api.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingInventory.Binding;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingInventory.Model;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalBindingInventory.Target;

class PhysicalBindingInventoryTest {
  @Test
  void ordinaryInventoryKeepsLegacyJsonAndFingerprint() throws Exception {
    var mapper = new ObjectMapper();
    var legacy =
        mapper.readTree(
            """
            {"contractVersion":1,"bindings":[{
              "bindingId":"a","modelId":"model-a","tenantId":"tenant",
              "target":{"dataSourceId":"source","catalogName":"database","schemaName":"public",
                        "tableName":"records_a","isolationMode":"SHARED_DATABASE"},
              "namespaceId":null,"revision":0,"deletedAt":null,
              "model":{"appId":"app","formId":"form","status":"ACTIVE",
                       "latestVersion":2,"activeVersion":1,"revision":3,"deletedAt":null}
            }]}
            """);
    var inventory = snapshot(binding("a", "model-a", "records_a", null));
    assertEquals(inventory, mapper.treeToValue(legacy, PhysicalBindingInventory.class));
    assertEquals(legacy, mapper.readTree(mapper.writeValueAsBytes(inventory)));
    assertEquals(
        "ca7f5fd2913ed3d4393bdb4c955a604c82a46e0abb5a7ea2bca1b8a8425f3c73",
        inventory.fingerprint());
  }

  @Test
  void cleanupEvidenceIsBoundToRetiredTombstonesAndFingerprint() {
    var retired = binding("a", "model-a", "records_a", Instant.parse("2026-10-28T00:00:00Z"));
    var marked =
        new Binding(
            retired.bindingId(),
            retired.modelId(),
            retired.tenantId(),
            retired.target(),
            retired.namespaceId(),
            retired.revision(),
            retired.deletedAt(),
            retired.model(),
            "a".repeat(64));
    assertNotEquals(snapshot(retired).fingerprint(), snapshot(marked).fingerprint());
    var active = binding("b", "model-b", "records_b", null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Binding(
                active.bindingId(),
                active.modelId(),
                active.tenantId(),
                active.target(),
                active.namespaceId(),
                active.revision(),
                active.deletedAt(),
                active.model(),
                "a".repeat(64)));
  }

  @Test
  void fingerprintIsIndependentOfReadOrderAndRetainsDeletedOwnership() {
    var first = binding("a", "model-a", "records_a", null);
    var retired = binding("b", "model-b", "records_b", Instant.parse("2026-10-28T00:00:00Z"));
    var inventory = new PhysicalBindingInventory(1, List.of(retired, first));
    assertEquals(List.of(first, retired), inventory.bindings());
    assertEquals(
        new PhysicalBindingInventory(1, List.of(first, retired)).fingerprint(),
        inventory.fingerprint());
    assertNotEquals(
        new PhysicalBindingInventory(1, List.of(first)).fingerprint(), inventory.fingerprint());
    assertThrows(UnsupportedOperationException.class, () -> inventory.bindings().clear());
  }

  @Test
  void namespaceSpellingAndAdoptionIdentityRemainSignificant() {
    var original = binding("a", "model", "顧客 取引", null);
    var target = original.target();
    var renamed =
        new Binding(
            original.bindingId(),
            original.modelId(),
            original.tenantId(),
            new Target(
                target.dataSourceId(),
                target.catalogName(),
                "public ",
                target.tableName(),
                target.isolationMode()),
            null,
            0,
            null,
            original.model());
    final var adopted =
        new Binding(
            original.bindingId(),
            original.modelId(),
            original.tenantId(),
            target,
            "00000000-0000-0000-0000-000000000001",
            1,
            null,
            original.model());
    assertEquals("顧客 取引", original.target().tableName());
    assertEquals("public ", renamed.target().schemaName());
    assertNotEquals(snapshot(original).fingerprint(), snapshot(renamed).fingerprint());
    assertNotEquals(snapshot(original).fingerprint(), snapshot(adopted).fingerprint());
  }

  @Test
  void duplicateDeclaredTargetsCannotHideBehindDifferentIsolationModes() {
    var first = binding("a", "model-a", "records", null);
    var second =
        new Binding(
            "b",
            "model-b",
            "other-tenant",
            new Target(
                "source", "database", "public", "records", PhysicalIsolationMode.DEDICATED_SCHEMA),
            null,
            0,
            null,
            first.model());
    assertThrows(
        IllegalArgumentException.class,
        () -> new PhysicalBindingInventory(1, List.of(first, second)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PhysicalBindingInventory(
                1, List.of(first, binding("b", "model-a", "different_table", null))));
  }

  @Test
  void sourceAliasesRemainSeparateUntilNativeTargetsAreReviewed() {
    var first = binding("a", "model-a", "records", null);
    var second =
        new Binding(
            "b",
            "model-b",
            "tenant",
            new Target(
                "alias", "database", "public", "records", PhysicalIsolationMode.SHARED_DATABASE),
            null,
            0,
            null,
            first.model());
    assertEquals(2, new PhysicalBindingInventory(1, List.of(first, second)).bindings().size());
  }

  private static PhysicalBindingInventory snapshot(Binding binding) {
    return new PhysicalBindingInventory(1, List.of(binding));
  }

  private static Binding binding(String id, String model, String table, Instant deleted) {
    return new Binding(
        id,
        model,
        "tenant",
        new Target("source", "database", "public", table, PhysicalIsolationMode.SHARED_DATABASE),
        null,
        0,
        deleted,
        new Model(
            "app",
            "form",
            deleted == null ? PhysicalModelStatus.ACTIVE : PhysicalModelStatus.RETIRED,
            2,
            1,
            3,
            deleted));
  }
}
