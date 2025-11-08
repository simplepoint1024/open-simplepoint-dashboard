package org.simplepoint.security.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.core.annotation.FormSchema;
import org.simplepoint.core.annotation.GenericsType;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.core.enums.ButtonType;
import org.simplepoint.core.enums.ButtonVariantTypes;

/**
 * Represents a button entity in the RBAC (Role-Based Access Control) system.
 *
 * <p>This class defines attributes related to a button, such as its ID.
 * It is mapped to the {@code access_remote_buttons} table in the database.</p>
 *
 * @author JinxuLiu
 * @since 1.0
 */
@Data
@Entity
@Table(name = "security_actions")
@EqualsAndHashCode(callSuper = true)
@FormSchema(genericsTypes = {
    @GenericsType(name = "id", value = String.class),
})
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "按钮对象", description = "用于表示系统中的按钮项")
public class Button extends BaseEntityImpl<String> {
  /**
   * The text displayed on the button.
   * This can be a label or any other text that describes the button's purpose.
   */
  @Schema(title = "按钮文本", description = "按钮上显示的文本", maxLength = 50, minLength = 1)
  @Column(nullable = false, length = 50)
  private String text;

  /**
   * The access value associated with the button, which can be used to control access permissions.
   * This can be a string representing a permission or role that is required to use the button.
   */
  @Schema(title = "按钮权限值", description = "按钮的权限值，用于控制访问权限", maxLength = 100, minLength = 1)
  @Column(nullable = false, length = 100)
  private String accessValue;
  /**
   * The title of the button, which can be used to display text on the button.
   * This can be a short description or label for the button.
   */
  @Column(nullable = false, length = 50)
  @Schema(title = "按钮标题", description = "按钮的标题，用于显示在按钮上", maxLength = 50, minLength = 1)
  private String title;
  /**
   * The unique identifier for the button, which can be used to reference it in the system.
   * This can be a UUID or any other unique string.
   */
  @Column(nullable = false, unique = true, length = 50)
  @Schema(title = "按钮唯一标识", description = "按钮的唯一标识符，用于在系统中引用", maxLength = 50, minLength = 1)
  private String key;
  /**
   * The type of the button, which can be used to define its behavior and appearance.
   * This can be DEFAULT, PRIMARY, DASHED, LINK, TEXT, etc.
   */
  @Column(nullable = false, length = 100)
  @Schema(title = "按钮类型", description = "按钮的类型，用于定义其行为和外观")
  private ButtonType type;
  /**
   * The color of the button, which can be used to define its appearance.
   * This can be a color name, hex code, or any other color representation.
   */
  @Column(nullable = false)
  @Schema(title = "按钮颜色", description = "按钮的颜色，用于定义其外观", maxLength = 20, minLength = 1)
  private String color;
  /**
   * The variant type of the button, which defines its style and appearance.
   * This can be OUTLINED, DASHED, SOLID, FILLED, TEXT, LINK, etc.
   */
  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  @Schema(title = "按钮变体类型", description = "按钮的变体类型，用于定义其样式和外观")
  private ButtonVariantTypes variant;
  /**
   * The icon associated with the button.
   * This can be a string representing the icon name or a URL to the icon image.
   */
  @Schema(title = "按钮图标", description = "按钮的图标，用于增强视觉效果", maxLength = 100, minLength = 1)
  private String icon;

  /**
   * The sort order of the button, which can be used to determine its position in a list or menu.
   * This is an integer value that defines the order in which the button appears.
   */
  @Column(nullable = false)
  @Schema(title = "按钮排序", description = "按钮的排序顺序，用于确定其在列表或菜单中的位置")
  private Integer sort;
  @Schema(
      title = "按钮权限标识",
      description = "按钮的权限标识，用于系统权限控制",
      maxLength = 512,
      minLength = 1,
      example = "btn:edit:user"
  )
  private String authority;
}
