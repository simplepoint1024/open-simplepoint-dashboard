package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.ApplicationFormApiVersion;
import org.simplepoint.plugin.app.form.api.model.FormComponentDescriptor;
import org.simplepoint.plugin.app.form.api.service.FormComponentContributor;

/** Verifies trusted component contributions are composed without silent replacement. */
class DefaultFormComponentRegistryTest {

  @Test
  void addsVersionedContributorContracts() {
    FormComponentDescriptor custom = descriptor("field.custom");
    FormComponentContributor contributor = () -> List.of(custom);

    DefaultFormComponentRegistry registry = new DefaultFormComponentRegistry(
        List.of(contributor)
    );

    assertThat(registry.find(custom.type(), custom.version())).contains(custom);
  }

  @Test
  void rejectsDuplicateTypeAndVersion() {
    FormComponentContributor contributor = () -> List.of(descriptor("field.text"));

    assertThatThrownBy(() -> new DefaultFormComponentRegistry(List.of(contributor)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("field.text@1");
  }

  private static FormComponentDescriptor descriptor(final String type) {
    return new FormComponentDescriptor(
        type,
        ApplicationFormApiVersion.RENDERER_V1,
        false,
        false,
        Set.of(),
        Map.of(),
        Set.of(),
        Set.of()
    );
  }
}
