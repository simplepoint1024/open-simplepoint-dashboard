package org.simplepoint.plugin.rbac.menu.api.vo;

import lombok.Data;

/**
 * Represents a micro module item with its name and entry point.
 *
 * @author JinxuLiu
 * @since 1.0
 */
@Data
public class MicroModuleItemVo {
  private String name;
  private String entry;

  /**
   * Default constructor.
   */
  public MicroModuleItemVo() {}

  /**
   * Parameterized constructor to initialize a micro module item.
   *
   * @param name  the name of the micro module
   * @param entry the entry point of the micro module
   */
  public MicroModuleItemVo(String name, String entry) {
    this.name = name;
    this.entry = entry;
  }
}
