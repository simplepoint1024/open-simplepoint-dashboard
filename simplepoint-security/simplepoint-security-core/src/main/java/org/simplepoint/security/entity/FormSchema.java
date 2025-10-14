package org.simplepoint.security.entity;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;

/**
 * Form schema entity for dynamic forms.
 *
 * <p>This class represents the schema of a form, including the associated entity class name
 * JSON Schema reference: <a href="https://json-schema.org/understanding-json-schema/">JSON SCHEMA</a>
 */
@Data
@Entity
@EqualsAndHashCode(callSuper = true)
@Table(name = "security_form_schemas")
@Schema(title = "表单/表格元数据", description = "包含访问元数据和列元数据的数组")
public class FormSchema extends BaseEntityImpl<String> {

  @Column(nullable = false)
  @Schema(title = "实体类名", description = "关联的实体类全名")
  private String entityClassName;

  @Column(length = 5000, nullable = false)
  @Schema(title = "表单 JSON Schema", description = "用于动态生成表单的 JSON Schema")
  private ObjectNode schemaJson;
}
