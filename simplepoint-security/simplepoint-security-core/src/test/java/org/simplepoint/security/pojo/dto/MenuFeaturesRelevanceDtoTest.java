package org.simplepoint.security.pojo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class MenuFeaturesRelevanceDtoTest {

  @Test
  void resolvedFeatureCodes_returnsFeatureCodes_whenSet() {
    MenuFeaturesRelevanceDto dto = new MenuFeaturesRelevanceDto("menu1", Set.of("f1", "f2"));
    assertThat(dto.resolvedFeatureCodes()).containsExactlyInAnyOrder("f1", "f2");
  }

  @Test
  @SuppressWarnings("deprecation")
  void resolvedFeatureCodes_fallsBackToPermissionAuthority_whenFeatureCodesEmpty() {
    MenuFeaturesRelevanceDto dto = new MenuFeaturesRelevanceDto();
    dto.setMenuId("menu1");
    dto.setPermissionAuthority(Set.of("perm1"));
    assertThat(dto.resolvedFeatureCodes()).containsExactly("perm1");
  }

  @Test
  void resolvedFeatureCodes_returnsNull_whenBothEmpty() {
    MenuFeaturesRelevanceDto dto = new MenuFeaturesRelevanceDto();
    assertThat(dto.resolvedFeatureCodes()).isNull();
  }

  @Test
  void constructor_setsFields() {
    MenuFeaturesRelevanceDto dto = new MenuFeaturesRelevanceDto("menuX", Set.of("code1"));
    assertThat(dto.getMenuId()).isEqualTo("menuX");
    assertThat(dto.getFeatureCodes()).containsExactly("code1");
  }
}
