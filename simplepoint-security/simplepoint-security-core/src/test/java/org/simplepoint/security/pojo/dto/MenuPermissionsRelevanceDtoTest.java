package org.simplepoint.security.pojo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class MenuPermissionsRelevanceDtoTest {

  @Test
  void constructor_withParams_setsFields() {
    MenuPermissionsRelevanceDto dto = new MenuPermissionsRelevanceDto("menu1", Set.of("perm.read"));
    assertThat(dto.getMenuId()).isEqualTo("menu1");
    assertThat(dto.getPermissionAuthority()).containsExactly("perm.read");
  }

  @Test
  void defaultConstructor_fieldsAreNull() {
    MenuPermissionsRelevanceDto dto = new MenuPermissionsRelevanceDto();
    assertThat(dto.getMenuId()).isNull();
    assertThat(dto.getPermissionAuthority()).isNull();
  }

  @Test
  void setters_workCorrectly() {
    MenuPermissionsRelevanceDto dto = new MenuPermissionsRelevanceDto();
    dto.setMenuId("menu2");
    dto.setPermissionAuthority(Set.of("perm.write", "perm.delete"));
    assertThat(dto.getMenuId()).isEqualTo("menu2");
    assertThat(dto.getPermissionAuthority()).containsExactlyInAnyOrder("perm.write", "perm.delete");
  }
}
