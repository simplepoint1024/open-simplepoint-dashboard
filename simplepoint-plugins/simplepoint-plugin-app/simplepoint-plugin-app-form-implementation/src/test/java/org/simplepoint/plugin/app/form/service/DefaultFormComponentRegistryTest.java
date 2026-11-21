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

  @Test
  void authoringCatalogDoesNotAdvertiseTheUnreleasedReferenceRuntime() {
    var context = org.mockito.Mockito.mock(FormRequestContext.class);
    org.mockito.Mockito.when(context.requireActor()).thenReturn(new FormRequestContext.FormActor("tenant", "user"));
    var access = org.mockito.Mockito.mock(org.simplepoint.plugin.app.form.api.service.ApplicationAccessPort.class);
    var registry = new DefaultFormComponentRegistry();
    var catalog = new ApplicationFormCatalogServiceImpl(null, context, access, registry);
    assertThat(registry.find("field.reference", "1")).isPresent();
    assertThat(catalog.designer("app").components()).noneMatch(component -> "field.reference".equals(component.type()));
    org.mockito.Mockito.verify(access).requireApplication("tenant", "app");
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
