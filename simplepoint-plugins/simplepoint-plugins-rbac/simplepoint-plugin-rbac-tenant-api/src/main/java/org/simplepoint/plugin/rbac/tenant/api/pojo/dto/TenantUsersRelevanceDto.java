package org.simplepoint.plugin.rbac.tenant.api.pojo.dto;

import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;
import lombok.Data;

/**
 * Tenant user relevance dto.
 */
@Data
public class TenantUsersRelevanceDto {
  private String tenantId;

  @Schema(
      description = "通过远程选人组件配置的租户成员",
      extensions = {
          @Extension(name = "x-ui", properties = {
              @ExtensionProperty(name = "widget", value = "UserPicker"),
              @ExtensionProperty(
                  name = "options",
                  value = "{\"selectionMode\":\"multiple\","
                      + "\"endpoint\":\"/common/users/picker/items\","
                      + "\"resolveEndpoint\":\"/common/users/picker/selected\","
                      + "\"pageSize\":20,\"minSearchLength\":3}",
                  parseValue = true
              )
          })
      }
  )
  private Set<String> userIds;
}
