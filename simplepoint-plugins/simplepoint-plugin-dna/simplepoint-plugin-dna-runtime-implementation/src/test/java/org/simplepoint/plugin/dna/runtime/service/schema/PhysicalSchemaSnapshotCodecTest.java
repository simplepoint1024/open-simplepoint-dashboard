package org.simplepoint.plugin.dna.runtime.service.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalColumnSnapshot;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalIndexSnapshot;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalStorageType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalForeignKeySnapshot;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalForeignKeySnapshot.Action;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalForeignKeySnapshot.Deferrability;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalTableSnapshot;

class PhysicalSchemaSnapshotCodecTest {

  private final PhysicalSchemaSnapshotCodec codec = new PhysicalSchemaSnapshotCodec(
      new ObjectMapper().findAndRegisterModules()
  );

  @Test
  void oldSnapshotsKeepTheirExactJsonAndChecksum() throws Exception {
    var mapper = new ObjectMapper();
    var snapshot = codec.snapshot(true, List.of(), List.of(), null);
    var legacy = "{\"exists\":true,\"columns\":[],\"indexes\":[],\"tableComment\":null,\"checksum\":\""
        + snapshot.checksum() + "\"}";
    assertThat(mapper.writeValueAsString(snapshot)).isEqualTo(legacy);
    assertThat(mapper.readValue(legacy, PhysicalTableSnapshot.class)).isEqualTo(snapshot);
    // The hash input must remain exactly the original four properties, in their historical order.
    var digest = java.security.MessageDigest.getInstance("SHA-256").digest(
        "{\"exists\":true,\"columns\":[],\"indexes\":[],\"tableComment\":null}"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThat(snapshot.checksum()).isEqualTo(java.util.HexFormat.of().formatHex(digest));
  }

  @Test
  void constraintRemovalTargetRebindingAndActionChangesAreVisibleInChecksums() throws Exception {
    var original = reference("customer_fk", "customers", Action.RESTRICT);
    var second = reference("supplier_fk", "suppliers", Action.RESTRICT);
    var baseline = codec.snapshot(true, List.of(), List.of(), null, List.of(original, second));
    assertThat(codec.snapshot(true, List.of(), List.of(), null, List.of(second, original)).checksum())
        .isEqualTo(baseline.checksum());
    for (var changed : List.of(List.<PhysicalForeignKeySnapshot>of(), List.of(original),
        List.of(reference("customer_fk", "different_customers", Action.RESTRICT), second),
        List.of(reference("customer_fk", "customers", Action.CASCADE), second))) {
      assertThat(codec.snapshot(true, List.of(), List.of(), null, changed).checksum())
          .isNotEqualTo(baseline.checksum());
    }
    var mapper = new ObjectMapper();
    assertThat(mapper.readValue(mapper.writeValueAsString(baseline), PhysicalTableSnapshot.class))
        .isEqualTo(baseline);
    assertThatThrownBy(() -> codec.snapshot(false, List.of(), List.of(), null, List.of(original)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> codec.snapshot(true, List.of(), List.of(), null, List.of(original, original)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static PhysicalForeignKeySnapshot reference(String name, String table, Action delete) {
    return new PhysicalForeignKeySnapshot(name, List.of("tenant_id", "customer_id"), "business", "public",
        table, List.of("tenant_id", "id"), Action.RESTRICT, delete, Deferrability.NOT_DEFERRABLE);
  }

  @Test
  void producesTheSameChecksumRegardlessOfJdbcMetadataOrder() {
    var first = codec.snapshot(
        true,
        List.of(column("z_field"), column("a_field")),
        List.of(index("idx_z", "z_field"), index("idx_a", "a_field")),
        "Form records"
    );
    var second = codec.snapshot(
        true,
        List.of(column("a_field"), column("z_field")),
        List.of(index("idx_a", "a_field"), index("idx_z", "z_field")),
        "Form records"
    );

    assertThat(second.checksum()).isEqualTo(first.checksum());
    assertThat(second.columns()).extracting(PhysicalColumnSnapshot::name)
        .containsExactly("a_field", "z_field");
  }

  private static PhysicalColumnSnapshot column(final String name) {
    return new PhysicalColumnSnapshot(
        name,
        PhysicalStorageType.STRING,
        64,
        null,
        null,
        true,
        null,
        null
    );
  }

  private static PhysicalIndexSnapshot index(final String name, final String column) {
    return new PhysicalIndexSnapshot(name, List.of(column), false, false);
  }
}
