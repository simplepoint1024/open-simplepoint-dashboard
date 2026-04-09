package org.simplepoint.plugin.dna.core.api.constants;

/**
 * Route constants for DNA management endpoints.
 */
public final class DnaPaths {

  public static final String BASE = "/dna";

  public static final String PLATFORM_BASE = "/platform/dna";

  public static final String DRIVERS = BASE + "/drivers";

  public static final String PLATFORM_DRIVERS = PLATFORM_BASE + "/drivers";

  public static final String DATA_SOURCES = BASE + "/data-sources";

  public static final String PLATFORM_DATA_SOURCES = PLATFORM_BASE + "/data-sources";

  public static final String METADATA = BASE + "/metadata";

  public static final String PLATFORM_METADATA = PLATFORM_BASE + "/metadata";

  public static final String DIALECTS = BASE + "/dialects";

  public static final String PLATFORM_DIALECTS = PLATFORM_BASE + "/dialects";

  private DnaPaths() {
  }
}
