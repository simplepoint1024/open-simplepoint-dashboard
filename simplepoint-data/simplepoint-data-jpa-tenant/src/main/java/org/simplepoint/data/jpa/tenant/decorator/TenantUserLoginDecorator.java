//package org.simplepoint.data.jpa.tenant.decorator;
//
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.node.ObjectNode;
//import java.util.Map;
//import org.simplepoint.api.security.base.BaseUser;
//import org.simplepoint.data.datasource.context.DataSourceContextHolder;
//import org.simplepoint.security.decorator.UserLoginDecorator;
//import org.simplepoint.security.entity.User;
//import org.springframework.security.core.userdetails.UserDetails;
//
///**
// * Tenant-specific implementation of UserLoginDecorator.
// * 租户特定的用户登录装饰器实现
// */
//public class TenantUserLoginDecorator implements UserLoginDecorator {
//  @Override
//  public String resolveUsername(String tenantUsername, Map<String, String> ext) {
//    // 如果用户名中包含 '@'，则将 '@' 前的部分作为租户标识
//    if (tenantUsername.contains("@")) {
//      var tenant = tenantUsername.substring(0, tenantUsername.indexOf("@"));
//      DataSourceContextHolder.set(tenant);
//      ext.put("tenant", tenant);
//      return tenantUsername.substring(tenantUsername.indexOf("@") + 1);
//    }
//    // 如果用户名中不包含 '@'，则使用当前数据源上下文作为租户标识
//    ext.put("tenant", DataSourceContextHolder.getDefaultDataSourceKey());
//    return tenantUsername;
//  }
//
//  @Override
//  public UserDetails afterLogin(UserDetails userDetails, Map<String, String> ext) {
//    if (ext.containsKey("tenant")) {
//      if (userDetails instanceof User user) {
//        user.getDecorator().put("tenant", ext.get("tenant"));
//      }
//    }
//    return userDetails;
//  }
//
//  @Override
//  public <T extends BaseUser> String resolveCacheKey(String cacheKey, T user) {
//    if (user instanceof User currentUser) {
//      ObjectNode decorator = currentUser.getDecorator();
//      if (decorator != null) {
//        JsonNode tenant = decorator.get("tenant");
//        if (tenant != null) {
//          return tenant.asText() + ":" + cacheKey;
//        }
//      }
//    }
//    throw new IllegalStateException("Tenant information is missing in user decorator");
//  }
//
//  @Override
//  public String resolveCacheKey(String cacheKey) {
//    return DataSourceContextHolder.getDefaultDataSourceKey() + ":" + cacheKey;
//  }
//}
