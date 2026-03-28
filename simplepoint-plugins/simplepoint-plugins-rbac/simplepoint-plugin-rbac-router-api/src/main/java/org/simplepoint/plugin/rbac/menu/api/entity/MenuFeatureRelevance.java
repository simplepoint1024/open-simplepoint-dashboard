package org.simplepoint.plugin.rbac.menu.api.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;

/**
 * Represents the relationship between menus and features.
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Entity
@Table(name = "simpoint_ac_permissions_menu_rel")
@Schema(title = "菜单功能关联实体", description = "表示菜单与功能之间的关联关系")
public class MenuFeatureRelevance extends BaseEntityImpl<String> {

  @Column(name = "menu_id")
  private String menuId;

  @Column(name = "permission_authority")
  private String featureCode;

  public MenuFeatureRelevance(String menuId, String featureCode) {
    this.menuId = menuId;
    this.featureCode = featureCode;
  }

  public MenuFeatureRelevance() {
  }
}
