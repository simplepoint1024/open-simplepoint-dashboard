package org.simplepoint.plugin.rbac.tenant.api.vo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TenantVosTest {

  @Test
  void dictionaryOptionVo_fields() {
    DictionaryOptionVo vo = new DictionaryOptionVo("1", "Active");
    assertThat(vo.value()).isEqualTo("1");
    assertThat(vo.label()).isEqualTo("Active");
  }

  @Test
  void dictionaryOptionVo_equality() {
    DictionaryOptionVo v1 = new DictionaryOptionVo("1", "Active");
    DictionaryOptionVo v2 = new DictionaryOptionVo("1", "Active");
    assertThat(v1).isEqualTo(v2);
  }

  @Test
  void namedTenantVo_fields() {
    NamedTenantVo vo = new NamedTenantVo("tenant-001", "Acme Corp");
    assertThat(vo.tenantId()).isEqualTo("tenant-001");
    assertThat(vo.tenantName()).isEqualTo("Acme Corp");
  }

  @Test
  void userRelevanceVo_fields() {
    UserRelevanceVo vo = new UserRelevanceVo("u1", "Alice", "alice@example.com", "13800138000");
    assertThat(vo.id()).isEqualTo("u1");
    assertThat(vo.name()).isEqualTo("Alice");
    assertThat(vo.email()).isEqualTo("alice@example.com");
    assertThat(vo.phoneNumber()).isEqualTo("13800138000");
  }
}
