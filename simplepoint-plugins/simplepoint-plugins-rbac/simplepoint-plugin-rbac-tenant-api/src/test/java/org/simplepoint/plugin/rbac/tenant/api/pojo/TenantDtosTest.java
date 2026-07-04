package org.simplepoint.plugin.rbac.tenant.api.pojo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.ApplicationResourcesRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.PackageApplicationsRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.TenantPackagesRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.TenantUsersRelevanceDto;

class TenantDtosTest {

  @Test
  void applicationResourcesRelevanceDto_setterGetter() {
    ApplicationResourcesRelevanceDto dto = new ApplicationResourcesRelevanceDto();
    dto.setApplicationCode("app1");
    dto.setResourceCodes(Set.of("resources.view", "resources.edit"));
    assertThat(dto.getApplicationCode()).isEqualTo("app1");
    assertThat(dto.getResourceCodes()).containsExactlyInAnyOrder("resources.view", "resources.edit");
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
