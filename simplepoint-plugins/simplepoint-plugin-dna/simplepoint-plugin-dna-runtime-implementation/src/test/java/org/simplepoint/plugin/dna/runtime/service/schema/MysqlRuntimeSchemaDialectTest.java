package org.simplepoint.plugin.dna.runtime.service.schema;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;

class MysqlRuntimeSchemaDialectTest {

  private final MysqlRuntimeSchemaDialect dialect = new MysqlRuntimeSchemaDialect(
      new PhysicalSchemaSnapshotCodec(new ObjectMapper().findAndRegisterModules())
  );

  @Test
  void reservesUtf8mb4RowCapacityForSystemFieldsAndRowOverhead() {
    assertThatCode(() -> dialect.validateSchema(schema(14_685)))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> dialect.validateSchema(schema(14_686)))
        .isInstanceOfSatisfying(DnaRuntimeException.class, exception ->
            org.assertj.core.api.Assertions.assertThat(exception.errorCode())
                .isEqualTo(DnaRuntimeErrorCode.SCHEMA_INVALID)
        );
  }

  private static PhysicalSchemaDefinition schema(final int length) {
    return new PhysicalSchemaDefinition(
        DnaRuntimeApiVersion.CURRENT,
        List.of(new LogicalFieldDefinition(
            "field-title",
            "title",
            "Title",
            LogicalFieldType.STRING,
            true,
            length,
            null,
            null,
            null
        )),
        List.of()
    );
  }
}
