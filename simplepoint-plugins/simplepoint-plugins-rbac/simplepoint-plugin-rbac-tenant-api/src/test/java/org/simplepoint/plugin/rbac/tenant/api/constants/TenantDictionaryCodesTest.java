package org.simplepoint.plugin.rbac.tenant.api.constants;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TenantDictionaryCodesTest {

  @Test
  void tenantType_constantValue() {
    assertThat(TenantDictionaryCodes.TENANT_TYPE).isEqualTo("tenant.type");
  }

  @Test
  void organizationType_constantValue() {
    assertThat(TenantDictionaryCodes.ORGANIZATION_TYPE).isEqualTo("organization.type");
  }
}
