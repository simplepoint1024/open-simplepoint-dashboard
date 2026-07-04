package org.simplepoint.plugin.rbac.core.api.pojo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.RoleResourceGrantDto;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.UserRoleRelevanceDto;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.AccessCenterResourceNodeVo;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RoleRelevanceVo;

class RbacCoreDtosAndVosTest {

  @Test
  void roleResourceGrantDto_setterGetter() {
    RoleResourceGrantDto dto = new RoleResourceGrantDto();
    dto.setRoleId("role1");
    dto.setResourceCodes(Set.of("resources.read", "resources.write"));
    assertThat(dto.getRoleId()).isEqualTo("role1");
    assertThat(dto.getResourceCodes()).containsExactlyInAnyOrder("resources.read", "resources.write");
  }

  @Test
  void userRoleRelevanceDto_setterGetter() {
    UserRoleRelevanceDto dto = new UserRoleRelevanceDto();
    dto.setUserId("user1");
    dto.setRoleIds(Set.of("role1", "role2"));
    assertThat(dto.getUserId()).isEqualTo("user1");
    assertThat(dto.getRoleIds()).containsExactlyInAnyOrder("role1", "role2");
  }

  @Test
  void accessCenterResourceNodeVo_setterGetter() {
    AccessCenterResourceNodeVo vo = new AccessCenterResourceNodeVo();
    vo.setId("res1");
    vo.setCode("users.view");
    vo.setResourceCode("users.view");
    vo.setGrantable(true);
    vo.getResourceCodes().add("users.view");
    assertThat(vo.getId()).isEqualTo("res1");
    assertThat(vo.getCode()).isEqualTo("users.view");
    assertThat(vo.getResourceCodes()).containsExactly("users.view");
    assertThat(vo.isGrantable()).isTrue();
  }

  @Test
  void roleRelevanceVo_paramConstructor() {
    RoleRelevanceVo vo = new RoleRelevanceVo("r1", "Admin", "ROLE_ADMIN", "Administrator role");
    assertThat(vo.getId()).isEqualTo("r1");
    assertThat(vo.getName()).isEqualTo("Admin");
    assertThat(vo.getAuthority()).isEqualTo("ROLE_ADMIN");
    assertThat(vo.getDescription()).isEqualTo("Administrator role");
  }

  @Test
  void roleRelevanceVo_defaultConstructor() {
    RoleRelevanceVo vo = new RoleRelevanceVo();
    assertThat(vo.getId()).isNull();
    assertThat(vo.getName()).isNull();
  }

  @Test
  void roleRelevanceVo_setterGetter() {
    RoleRelevanceVo vo = new RoleRelevanceVo();
    vo.setId("r2");
    vo.setName("Viewer");
    vo.setAuthority("ROLE_VIEWER");
    vo.setDescription("View-only role");
    assertThat(vo.getId()).isEqualTo("r2");
    assertThat(vo.getName()).isEqualTo("Viewer");
    assertThat(vo.getAuthority()).isEqualTo("ROLE_VIEWER");
    assertThat(vo.getDescription()).isEqualTo("View-only role");
  }
}
