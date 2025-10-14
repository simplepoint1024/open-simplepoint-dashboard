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
public enum ButtonVariantTypes {
  OUTLINED("outlined"),
  DASHED("dashed"),
  SOLID("solid"),
  FILLED("filled"),
  TEXT("text"),
  LINK("link"),
  ;

  @JsonValue
  private final String value;

  /**
   * Constructs a ButtonVariantTypes enum with the specified value.
   *
   * @param value the string representation of the button variant type
   */
  ButtonVariantTypes(String value) {
    this.value = value;
  }

  @Override
  public String toString() {
    return this.value;
  }
}
