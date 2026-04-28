package org.simplepoint.plugin.rbac.core.api.pojo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.RolePermissionsRelevanceDto;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.UserRoleRelevanceDto;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.PermissionsRelevanceVo;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.RoleRelevanceVo;

class RbacCoreDtosAndVosTest {

  @Test
  void rolePermissionsRelevanceDto_setterGetter() {
    RolePermissionsRelevanceDto dto = new RolePermissionsRelevanceDto();
    dto.setRoleId("role1");
    dto.setPermissionAuthority(Set.of("perm.read", "perm.write"));
    assertThat(dto.getRoleId()).isEqualTo("role1");
    assertThat(dto.getPermissionAuthority()).containsExactlyInAnyOrder("perm.read", "perm.write");
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
  void permissionsRelevanceVo_paramConstructor() {
    PermissionsRelevanceVo vo = new PermissionsRelevanceVo("p1", "Read Users", "users.read", "Can read users", 0);
    assertThat(vo.getId()).isEqualTo("p1");
    assertThat(vo.getName()).isEqualTo("Read Users");
    assertThat(vo.getAuthority()).isEqualTo("users.read");
    assertThat(vo.getDescription()).isEqualTo("Can read users");
    assertThat(vo.getType()).isEqualTo(0);
  }

  @Test
  void permissionsRelevanceVo_defaultConstructor() {
    PermissionsRelevanceVo vo = new PermissionsRelevanceVo();
    assertThat(vo.getId()).isNull();
    assertThat(vo.getName()).isNull();
  }

  @Test
  void permissionsRelevanceVo_setterGetter() {
    PermissionsRelevanceVo vo = new PermissionsRelevanceVo();
    vo.setId("p2");
    vo.setName("Write");
    vo.setAuthority("users.write");
    vo.setType(1);
    assertThat(vo.getId()).isEqualTo("p2");
    assertThat(vo.getName()).isEqualTo("Write");
    assertThat(vo.getAuthority()).isEqualTo("users.write");
    assertThat(vo.getType()).isEqualTo(1);
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
