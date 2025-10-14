package org.simplepoint.core.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.core.annotation.FormSchema;
import org.simplepoint.core.annotation.GenericsType;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;

/**
 * Represents a message in the internationalization system.
 * This class extends BaseEntityImpl to inherit common entity properties.
 */
@Data
@Entity
@EqualsAndHashCode(callSuper = true)
@FormSchema(genericsTypes = {
    @GenericsType(name = "id", value = String.class),
})
@Table(name = "i18n_messages", indexes = {
    @Index(name = "idx_i18n_messages_code", columnList = "code"),
    @Index(name = "idx_i18n_messages_locale", columnList = "locale"),
})
@Schema(title = "国际化消息对象", description = "国际化消息对象")
public class Message extends BaseEntityImpl<String> {
  @Schema(title = "语言环境", description = "语言环境")
  private String locale;
  @Schema(title = "类型", description = "类型")
  private String type;
  @Schema(title = "代码", description = "代码")
  private String code;
  @Schema(title = "文本", description = "文本")
  private String text;
}
