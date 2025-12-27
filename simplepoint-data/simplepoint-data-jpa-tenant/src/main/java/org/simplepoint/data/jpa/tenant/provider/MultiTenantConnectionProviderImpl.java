//package org.simplepoint.data.jpa.tenant.provider;
//
//import java.sql.Connection;
//import java.sql.SQLException;
//import javax.sql.DataSource;
//import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
//import org.simplepoint.data.datasource.context.DataSourceContextHolder;
//
//public class MultiTenantConnectionProviderImpl
//    implements MultiTenantConnectionProvider<String> {
//
//
//  /**
//   * 获取默认连接（Hibernate 启动时会调用）
//   */
//  @Override
//  public Connection getAnyConnection() throws SQLException {
//    // 默认数据源名称来自你的配置
//    return DataSourceContextHolder.getDefaultDataSource().getConnection();
//  }
//
//  /**
//   * 释放默认连接
//   */
//  @Override
//  public void releaseAnyConnection(Connection connection) throws SQLException {
//    connection.close();
//  }
//
//  /**
//   * 根据租户 ID 获取连接（核心逻辑）
//   */
//  @Override
//  public Connection getConnection(String tenantIdentifier) throws SQLException {
//    // 从你的动态数据源管理器获取 DataSource
//    DataSource dataSource = DataSourceContextHolder.getDataSource(tenantIdentifier);
//
//    if (dataSource == null) {
//      throw new SQLException("Unknown tenant: " + tenantIdentifier);
//    }
//
//    Connection connection = dataSource.getConnection();
//
//    // ✅ 如果你是 Schema-per-Tenant（可选）
//    // connection.setSchema(tenantIdentifier);
//
//    return connection;
//  }
//
//  /**
//   * 释放租户连接
//   */
//  @Override
//  public void releaseConnection(String tenantIdentifier, Connection connection) throws SQLException {
//    connection.close();
//  }
//
//  @Override
//  public boolean supportsAggressiveRelease() {
//    return false;
//  }
//
//  @Override
//  public boolean isUnwrappableAs(Class<?> unwrapType) {
//    return unwrapType.isAssignableFrom(getClass());
//  }
//
//  @Override
//  public <T> T unwrap(Class<T> unwrapType) {
//    if (unwrapType.isAssignableFrom(getClass())) {
//      return unwrapType.cast(this);
//    }
//    return null;
//  }
//}
