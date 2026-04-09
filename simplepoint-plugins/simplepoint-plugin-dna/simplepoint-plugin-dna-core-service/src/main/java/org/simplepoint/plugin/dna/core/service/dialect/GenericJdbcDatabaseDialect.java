package org.simplepoint.plugin.dna.core.service.dialect;

import org.simplepoint.plugin.dna.core.api.spi.JdbcDatabaseDialect;

/**
 * Generic fallback JDBC dialect used when no vendor-specific dialect matches.
 */
public class GenericJdbcDatabaseDialect extends AbstractJdbcDatabaseDialect {

  @Override
  public String code() {
    return "generic";
  }

  @Override
  public String name() {
    return "Generic JDBC";
  }

  @Override
  public String description() {
    return "Generic JDBC fallback dialect";
  }

  @Override
  public int order() {
    return 1000;
  }

  @Override
  public boolean supports(final SupportContext context) {
    return true;
  }
}
