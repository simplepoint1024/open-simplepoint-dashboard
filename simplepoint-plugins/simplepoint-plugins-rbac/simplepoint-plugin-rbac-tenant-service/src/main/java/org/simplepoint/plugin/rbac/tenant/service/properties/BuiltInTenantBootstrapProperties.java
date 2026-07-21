package org.simplepoint.plugin.rbac.tenant.service.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the first organization workspace and its representative users.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "simplepoint.tenant.bootstrap")
public class BuiltInTenantBootstrapProperties {

  private String organizationName = "SimplePoint 示例组织";

  private String organizationDescription = "系统初始化的组织租户，用于展示租户资源、角色和成员授权";

  private String ownerSubject = "simplepoint@mail.com";

  private String managerSubject = "manager@simplepoint.local";

  private String memberSubject = "member@simplepoint.local";
}
