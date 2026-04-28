package org.simplepoint.plugin.rbac.tenant.api.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TenantEntityFieldsTest {

  @Test
  void tenant_setterGetter() {
    Tenant tenant = new Tenant();
    tenant.setName("My Tenant");
    assertThat(tenant.getName()).isEqualTo("My Tenant");
  }

  @Test
  void organization_setterGetter() {
    Organization org = new Organization();
    org.setName("Dept A");
    org.setTenantId("tenant1");
    assertThat(org.getName()).isEqualTo("Dept A");
    assertThat(org.getTenantId()).isEqualTo("tenant1");
  }

  @Test
  void dictionary_setterGetter() {
    Dictionary dict = new Dictionary();
    dict.setName("Status");
    dict.setCode("status");
    assertThat(dict.getName()).isEqualTo("Status");
    assertThat(dict.getCode()).isEqualTo("status");
  }

  @Test
  void dictionaryItem_setterGetter() {
    DictionaryItem item = new DictionaryItem();
    item.setName("Active");
    item.setValue("1");
    item.setDictionaryCode("status");
    assertThat(item.getName()).isEqualTo("Active");
    assertThat(item.getValue()).isEqualTo("1");
    assertThat(item.getDictionaryCode()).isEqualTo("status");
  }

  @Test
  void feature_setterGetter() {
    Feature feature = new Feature();
    feature.setName("User Management");
    feature.setCode("user.management");
    assertThat(feature.getName()).isEqualTo("User Management");
    assertThat(feature.getCode()).isEqualTo("user.management");
  }

  @Test
  void application_setterGetter() {
    Application app = new Application();
    app.setName("My App");
    app.setCode("my-app");
    assertThat(app.getName()).isEqualTo("My App");
    assertThat(app.getCode()).isEqualTo("my-app");
  }

  @Test
  void applicationFeatureRelevance_setterGetter() {
    ApplicationFeatureRelevance rel = new ApplicationFeatureRelevance();
    rel.setApplicationCode("app1");
    rel.setFeatureCode("feature1");
    assertThat(rel.getApplicationCode()).isEqualTo("app1");
    assertThat(rel.getFeatureCode()).isEqualTo("feature1");
  }

  @Test
  void featurePermissionRelevance_setterGetter() {
    FeaturePermissionRelevance rel = new FeaturePermissionRelevance();
    rel.setFeatureCode("feature1");
    rel.setPermissionAuthority("perm.read");
    assertThat(rel.getFeatureCode()).isEqualTo("feature1");
    assertThat(rel.getPermissionAuthority()).isEqualTo("perm.read");
  }

  @Test
  void packageApplicationRelevance_setterGetter() {
    PackageApplicationRelevance rel = new PackageApplicationRelevance();
    rel.setPackageCode("pkg1");
    rel.setApplicationCode("app1");
    assertThat(rel.getPackageCode()).isEqualTo("pkg1");
    assertThat(rel.getApplicationCode()).isEqualTo("app1");
  }

  @Test
  void tenantPackageRelevance_setterGetter() {
    TenantPackageRelevance rel = new TenantPackageRelevance();
    rel.setTenantId("t1");
    rel.setPackageCode("pkg1");
    assertThat(rel.getTenantId()).isEqualTo("t1");
    assertThat(rel.getPackageCode()).isEqualTo("pkg1");
  }

  @Test
  void tenantUserRelevance_setterGetter() {
    TenantUserRelevance rel = new TenantUserRelevance();
    rel.setTenantId("t1");
    rel.setUserId("user1");
    assertThat(rel.getTenantId()).isEqualTo("t1");
    assertThat(rel.getUserId()).isEqualTo("user1");
  }

  @Test
  void pkg_setterGetter() {
    Package pkg = new Package();
    pkg.setName("Basic Plan");
    pkg.setCode("basic");
    pkg.setEnabled(true);
    assertThat(pkg.getName()).isEqualTo("Basic Plan");
    assertThat(pkg.getCode()).isEqualTo("basic");
    assertThat(pkg.getEnabled()).isTrue();
  }
}
