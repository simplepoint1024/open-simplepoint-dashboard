package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.DnaRuntimeApiVersion;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldDefinition;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.api.schema.PhysicalSchemaDefinition;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelBindingEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelVersionEntity;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelBindingRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelVersionRepository;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec.EncodedSchema;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

class RuntimeRecordModelResolverTest {

  @Test
  void resolvesAnExactHistoricalSnapshotAndCurrentFieldProjection() {
    Fixture fixture = fixture();

    RuntimeRecordModel model = fixture.resolver().resolve(
        fixture.actor(),
        "model-1",
        new RecordSchemaReference(1, fixture.requestedChecksum())
    );

    assertThat(model.requestedVersion()).isEqualTo(1);
    assertThat(model.activeVersion()).isEqualTo(2);
    assertThat(model.activeCode("title")).isEqualTo("subject");
  }

  @Test
  void rejectsChecksumThatDoesNotIdentifyTheRequestedVersion() {
    Fixture fixture = fixture();

    assertThatThrownBy(() -> fixture.resolver().resolve(
        fixture.actor(),
        "model-1",
        new RecordSchemaReference(
            1,
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        )
    )).isInstanceOfSatisfying(DnaRuntimeException.class, exception ->
        assertThat(exception.errorCode()).isEqualTo(
            DnaRuntimeErrorCode.RECORD_SCHEMA_INCOMPATIBLE
        ));
  }

  private static Fixture fixture() {
    final PhysicalModelRepository modelRepository = mock(PhysicalModelRepository.class);
    final PhysicalModelBindingRepository bindingRepository =
        mock(PhysicalModelBindingRepository.class);
    final PhysicalModelVersionRepository versionRepository =
        mock(PhysicalModelVersionRepository.class);
    PhysicalSchemaCodec codec = new PhysicalSchemaCodec(
        new ObjectMapper().findAndRegisterModules()
    );
    PhysicalSchemaDefinition requested = schema("title", 64);
    PhysicalSchemaDefinition active = schema("subject", 128);
    final EncodedSchema requestedEncoded = codec.encode(requested);
    final EncodedSchema activeEncoded = codec.encode(active);

    PhysicalModelEntity model = new PhysicalModelEntity();
    model.setId("model-1");
    model.setTenantId("tenant-a");
    model.setAppId("app-1");
    model.setFormId("form-1");
    model.setStatus(PhysicalModelStatus.ACTIVE);
    model.setActiveVersion(2);
    PhysicalModelBindingEntity binding = new PhysicalModelBindingEntity();
    binding.setPhysicalModelId("model-1");
    binding.setDataSourceId("source-1");
    binding.setCatalogName("");
    binding.setSchemaName("public");
    binding.setTableName("records");
    PhysicalModelVersionEntity requestedVersion = version(1, requestedEncoded);
    PhysicalModelVersionEntity activeVersion = version(2, activeEncoded);
    when(modelRepository.findActiveById("tenant-a", "model-1"))
        .thenReturn(Optional.of(model));
    when(bindingRepository.findActiveByModel("tenant-a", "model-1"))
        .thenReturn(Optional.of(binding));
    when(versionRepository.findActiveVersion("tenant-a", "model-1", 1))
        .thenReturn(Optional.of(requestedVersion));
    when(versionRepository.findActiveVersion("tenant-a", "model-1", 2))
        .thenReturn(Optional.of(activeVersion));

    return new Fixture(
        new RuntimeRecordModelResolver(
            modelRepository,
            bindingRepository,
            versionRepository,
            codec
        ),
        new RuntimeActor("tenant-a", "user-a"),
        requestedEncoded.checksum()
    );
  }

  private static PhysicalModelVersionEntity version(
      final int version,
      final EncodedSchema encoded
  ) {
    PhysicalModelVersionEntity entity = new PhysicalModelVersionEntity();
    entity.setVersionNumber(version);
    entity.setSchemaJson(encoded.json());
    entity.setSchemaChecksum(encoded.checksum());
    return entity;
  }

  private static PhysicalSchemaDefinition schema(final String code, final int length) {
    return new PhysicalSchemaDefinition(
        DnaRuntimeApiVersion.CURRENT,
        List.of(new LogicalFieldDefinition(
            "field-title",
            code,
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

  private record Fixture(
      RuntimeRecordModelResolver resolver,
      RuntimeActor actor,
      String requestedChecksum
  ) {
  }
}
