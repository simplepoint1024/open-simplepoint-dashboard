package org.simplepoint.plugin.dna.federation.api.constants;

/**
 * Route constants for DNA federation management endpoints.
 */
public final class DnaFederationPaths {

  public static final String BASE = "/dna/federation";

  public static final String PLATFORM_BASE = "/platform/dna/federation";

  public static final String CATALOGS = BASE + "/catalogs";

  public static final String PLATFORM_CATALOGS = PLATFORM_BASE + "/catalogs";

  public static final String SCHEMAS = BASE + "/schemas";

  public static final String PLATFORM_SCHEMAS = PLATFORM_BASE + "/schemas";

  public static final String VIEWS = BASE + "/views";

  public static final String PLATFORM_VIEWS = PLATFORM_BASE + "/views";

  public static final String QUERY_POLICIES = BASE + "/query-policies";

  public static final String PLATFORM_QUERY_POLICIES = PLATFORM_BASE + "/query-policies";

  public static final String QUERY_AUDITS = BASE + "/query-audits";

  public static final String PLATFORM_QUERY_AUDITS = PLATFORM_BASE + "/query-audits";

  public static final String SQL_CONSOLE = BASE + "/sql-console";

  public static final String PLATFORM_SQL_CONSOLE = PLATFORM_BASE + "/sql-console";

  private DnaFederationPaths() {
  }
}
