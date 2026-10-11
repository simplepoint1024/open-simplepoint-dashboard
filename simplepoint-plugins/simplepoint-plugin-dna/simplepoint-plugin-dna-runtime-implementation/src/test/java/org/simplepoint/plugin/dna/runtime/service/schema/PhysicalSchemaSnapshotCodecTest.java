package org.simplepoint.plugin.dna.runtime.service.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalColumnSnapshot;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalIndexSnapshot;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalStorageType;

class PhysicalSchemaSnapshotCodecTest {

  private final PhysicalSchemaSnapshotCodec codec = new PhysicalSchemaSnapshotCodec(
      new ObjectMapper().findAndRegisterModules()
  );

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
