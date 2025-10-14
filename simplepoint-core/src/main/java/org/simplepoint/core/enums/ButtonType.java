package org.simplepoint.core.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * Enum representing different types of button variants.
 * This enum is used to define the style and appearance of buttons in the RBAC system.
 *
 * @author JinxuLiu
 * @since 1.0
 */
@Getter
public enum ButtonType {
  DEFAULT("default"),
  PRIMARY("primary"),
  DASHED("dashed"),
  LINK("link"),
  TEXT("text");
  @JsonValue
  private final String value;

  /**
   * Constructs a ButtonType enum with the specified value.
   *
   * @param value the string representation of the button type
   */
  ButtonType(String value) {
    this.value = value;
  }

  @Override
  public String toString() {
    return this.value;
  }
}
