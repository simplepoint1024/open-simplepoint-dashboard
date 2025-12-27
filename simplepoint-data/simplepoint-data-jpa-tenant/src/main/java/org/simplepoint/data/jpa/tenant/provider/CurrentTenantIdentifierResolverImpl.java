//package org.simplepoint.data.jpa.tenant.provider;
//
//import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
//import org.simplepoint.data.datasource.context.DataSourceContextHolder;
//
///**
// * Custom implementation of CurrentTenantIdentifierResolver
// * to resolve the current tenant identifier from ThreadLocal context.
// */
//public class CurrentTenantIdentifierResolverImpl implements CurrentTenantIdentifierResolver<String> {
//
//  @Override
//  public String resolveCurrentTenantIdentifier() {
//    // ✅ 从 ThreadLocal 中获取当前租户
//    String tenantId = DataSourceContextHolder.get();
//
//    // ✅ Hibernate 启动阶段 / 无租户请求时，必须返回默认租户
//    if (tenantId == null || tenantId.isBlank()) {
//      return DataSourceContextHolder.getDefaultDataSourceKey();
//    }
//
//    return tenantId;
//  }
//
//  @Override
//  public boolean validateExistingCurrentSessions() {
//    // ✅ 返回 true：允许 Hibernate 在租户切换时继续使用当前 Session
//    return true;
//  }
//}
