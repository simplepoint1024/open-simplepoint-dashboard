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

  public static final String JDBC_USERS = BASE + "/jdbc-users";

  public static final String PLATFORM_JDBC_USERS = PLATFORM_BASE + "/jdbc-users";

  public static final String QUERY_TEMPLATES = BASE + "/query-templates";

  public static final String PLATFORM_QUERY_TEMPLATES = PLATFORM_BASE + "/query-templates";

  public static final String DATA_ASSETS = BASE + "/data-assets";

  public static final String PLATFORM_DATA_ASSETS = PLATFORM_BASE + "/data-assets";

  public static final String JDBC_DRIVER = BASE + "/jdbc-driver";

  public static final String PLATFORM_JDBC_DRIVER = PLATFORM_BASE + "/jdbc-driver";

  public static final String DATA_QUALITY = BASE + "/data-quality";

  public static final String PLATFORM_DATA_QUALITY = PLATFORM_BASE + "/data-quality";

  public static final String DATA_LINEAGE_NODES = BASE + "/data-lineage/nodes";

  public static final String PLATFORM_DATA_LINEAGE_NODES = PLATFORM_BASE + "/data-lineage/nodes";

  public static final String DATA_LINEAGE_EDGES = BASE + "/data-lineage/edges";

  public static final String PLATFORM_DATA_LINEAGE_EDGES = PLATFORM_BASE + "/data-lineage/edges";

  public static final String DASHBOARD = "/dna/dashboard";

  public static final String PLATFORM_DASHBOARD = "/platform/dna/dashboard";

  private DnaFederationPaths() {
  }
}
