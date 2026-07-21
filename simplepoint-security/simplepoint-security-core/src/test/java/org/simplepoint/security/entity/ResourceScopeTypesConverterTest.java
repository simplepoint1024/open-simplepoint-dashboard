package org.simplepoint.security.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ResourceScopeTypesConverterTest {

  private final ResourceScopeTypesConverter converter = new ResourceScopeTypesConverter();

  @Test
  void persistsScopesInStableOrder() {
    assertThat(converter.convertToDatabaseColumn(Set.of(
        ResourceScopeType.TENANT,
        ResourceScopeType.SYSTEM,
        ResourceScopeType.PERSONAL
    ))).isEqualTo("SYSTEM,TENANT,PERSONAL");
  }

  @Test
  void restoresScopeSetAndHandlesLegacyEmptyValue() {
    assertThat(converter.convertToEntityAttribute("TENANT, PERSONAL"))
        .containsExactlyInAnyOrder(ResourceScopeType.TENANT, ResourceScopeType.PERSONAL);
    assertThat(converter.convertToEntityAttribute(null)).isEmpty();
  }
}
