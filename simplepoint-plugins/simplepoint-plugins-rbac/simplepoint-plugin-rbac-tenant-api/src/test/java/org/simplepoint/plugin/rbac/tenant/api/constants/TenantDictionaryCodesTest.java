package org.simplepoint.plugin.rbac.tenant.api.constants;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TenantDictionaryCodesTest {

  @Test
  void organizationType_constantValue() {
    assertThat(TenantDictionaryCodes.ORGANIZATION_TYPE).isEqualTo("organization.type");
  }
}
