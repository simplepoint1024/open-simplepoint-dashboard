package org.simplepoint.security.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.simplepoint.core.annotation.FormSchema;
import org.simplepoint.core.annotation.GenericsType;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;

/**
 * Represents a remote module entity in the RBAC (Role-Based Access Control) system.
 *
 * <p>This class defines attributes related to a remote module, such as its name and entry point.
 * It is mapped to the {@code remote_modules} table in the database.</p>
 *
 * @author JinxuLiu
 * @since 1.0
 */
@Data
@Entity
@Table(name = "security_modules")
@EqualsAndHashCode(callSuper = true)
@FormSchema(genericsTypes = {
    @GenericsType(name = "id", value = String.class),
})
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(title = "远程模块对象", description = "用于加载微前端远程模块")
public class RemoteModule extends BaseEntityImpl<String> {
  @Column(nullable = false, unique = true)
  @Schema(title = "模块名称", description = "远程模块名称")
  private String name;
  @Column(nullable = false, unique = true)
  @Schema(title = "模块入口", description = "远程模块入口")
  private String entry;
  @Schema(title = "模块描述", description = "远程模块描述")
  private String description;
}
