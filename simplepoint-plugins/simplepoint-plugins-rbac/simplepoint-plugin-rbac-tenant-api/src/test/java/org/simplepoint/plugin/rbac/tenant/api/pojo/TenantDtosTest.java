package org.simplepoint.plugin.rbac.tenant.api.pojo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.ApplicationFeaturesRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.FeaturePermissionsRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.PackageApplicationsRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.TenantPackagesRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.TenantUsersRelevanceDto;

class TenantDtosTest {

  @Test
  void applicationFeaturesRelevanceDto_setterGetter() {
    ApplicationFeaturesRelevanceDto dto = new ApplicationFeaturesRelevanceDto();
    dto.setApplicationCode("app1");
    dto.setFeatureCodes(Set.of("f1", "f2"));
    assertThat(dto.getApplicationCode()).isEqualTo("app1");
    assertThat(dto.getFeatureCodes()).containsExactlyInAnyOrder("f1", "f2");
  }

  @Test
  void featurePermissionsRelevanceDto_setterGetter() {
    FeaturePermissionsRelevanceDto dto = new FeaturePermissionsRelevanceDto();
    dto.setFeatureCode("feature1");
    dto.setPermissionAuthority(Set.of("perm.read"));
    assertThat(dto.getFeatureCode()).isEqualTo("feature1");
    assertThat(dto.getPermissionAuthority()).containsExactly("perm.read");
  }

  @Test
  void packageApplicationsRelevanceDto_setterGetter() {
    PackageApplicationsRelevanceDto dto = new PackageApplicationsRelevanceDto();
    dto.setPackageCode("pkg1");
    dto.setApplicationCodes(Set.of("app1", "app2"));
    assertThat(dto.getPackageCode()).isEqualTo("pkg1");
    assertThat(dto.getApplicationCodes()).containsExactlyInAnyOrder("app1", "app2");
  }

  @Test
  void tenantPackagesRelevanceDto_setterGetter() {
    TenantPackagesRelevanceDto dto = new TenantPackagesRelevanceDto();
    dto.setTenantId("t1");
    dto.setPackageCodes(Set.of("pkg1", "pkg2"));
    assertThat(dto.getTenantId()).isEqualTo("t1");
    assertThat(dto.getPackageCodes()).containsExactlyInAnyOrder("pkg1", "pkg2");
  }

  @Test
  void tenantUsersRelevanceDto_setterGetter() {
    TenantUsersRelevanceDto dto = new TenantUsersRelevanceDto();
    dto.setTenantId("t1");
    dto.setUserIds(Set.of("user1", "user2"));
    assertThat(dto.getTenantId()).isEqualTo("t1");
    assertThat(dto.getUserIds()).containsExactlyInAnyOrder("user1", "user2");
  }
}
