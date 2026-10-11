package org.simplepoint.plugin.dna.runtime.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalIsolationMode;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalPlacementRequest;
import org.simplepoint.plugin.dna.runtime.configuration.DnaRuntimePlacementProperties;

class PhysicalPlacementSupportTest {

  @Test
  void tableNamesAreStablePortableAndDoNotExposeFormNames() {
    DefaultPhysicalTableNamingStrategy strategy = new DefaultPhysicalTableNamingStrategy();
    String first = strategy.tableName("4adbe1c6-c782-4c8f-a7fc-2cb575f6f35f");
    String second = strategy.tableName("4adbe1c6-c782-4c8f-a7fc-2cb575f6f35f");

    assertThat(first).isEqualTo(second);
    assertThat(first).hasSize(30).matches("[a-z][a-z0-9_]{29}");
  }

  @Test
  void resolvesOnlyAdministratorConfiguredLogicalPlacementKeys() {
    final DnaRuntimePlacementProperties properties = new DnaRuntimePlacementProperties();
    DnaRuntimePlacementProperties.Target target = new DnaRuntimePlacementProperties.Target();
    target.setDataSourceId("primary-forms");
    target.setSchemaName("forms");
    target.setIsolationMode(PhysicalIsolationMode.SHARED_DATABASE);
    properties.setTargets(Map.of("default", target));
    ConfiguredPhysicalBindingTargetResolver resolver =
        new ConfiguredPhysicalBindingTargetResolver(properties);

    assertThat(resolver.resolve(request("default")).dataSourceId())
        .isEqualTo("primary-forms");
    assertThatThrownBy(() -> resolver.resolve(request("client-data-source-id")))
        .isInstanceOfSatisfying(DnaRuntimeException.class, exception ->
            assertThat(exception.errorCode())
                .isEqualTo(DnaRuntimeErrorCode.PLACEMENT_UNAVAILABLE));
  }

  private static PhysicalPlacementRequest request(final String placementKey) {
    return new PhysicalPlacementRequest("tenant-a", "app-a", "form-a", placementKey);
  }
}
