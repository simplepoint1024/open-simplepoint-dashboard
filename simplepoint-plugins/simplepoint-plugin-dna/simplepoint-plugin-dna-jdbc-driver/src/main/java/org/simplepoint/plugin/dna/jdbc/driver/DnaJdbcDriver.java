package org.simplepoint.plugin.dna.jdbc.driver;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Standalone read-only JDBC driver for the DNA federation gateway.
 */
public final class DnaJdbcDriver implements Driver {

  private static final String DRIVER_NAME = "SimplePoint DNA JDBC Driver";

  private static final int MAJOR_VERSION = 1;

  private static final int MINOR_VERSION = 0;

  static {
    try {
      DriverManager.registerDriver(new DnaJdbcDriver());
    } catch (SQLException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  @Override
  public Connection connect(final String url, final Properties info) throws SQLException {
    if (!acceptsURL(url)) {
      return null;
    }
    DnaJdbcModels.ConnectionConfig config = DnaJdbcUrlParser.parse(url, info == null ? new Properties() : info);
    return DnaJdbcProxies.openConnection(config);
  }

  @Override
  public boolean acceptsURL(final String url) {
    return DnaJdbcUrlParser.accepts(url);
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(final String url, final Properties info) {
    return new DriverPropertyInfo[] {
        property("user", true, "系统用户邮箱或手机号"),
        property("password", true, "系统用户密码"),
        property("catalogCode", false, "可选默认数据源目录编码"),
        property("tenantId", false, "可选租户 ID"),
        property("contextId", false, "可选权限上下文 ID"),
        property("schema", false, "可选默认 Schema")
    };
  }

  @Override
  public int getMajorVersion() {
    return MAJOR_VERSION;
  }

  @Override
  public int getMinorVersion() {
    return MINOR_VERSION;
  }

  @Override
  public boolean jdbcCompliant() {
    return false;
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException("DNA JDBC 驱动不提供 JUL Logger");
  }

  static String driverName() {
    return DRIVER_NAME;
  }

  static String driverVersion() {
    return MAJOR_VERSION + "." + MINOR_VERSION;
  }

  private static DriverPropertyInfo property(
      final String name,
      final boolean required,
      final String description
  ) {
    DriverPropertyInfo info = new DriverPropertyInfo(name, null);
    info.required = required;
    info.description = description;
    return info;
  }
}
