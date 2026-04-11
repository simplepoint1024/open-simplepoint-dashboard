package org.simplepoint.plugin.dna.federation.api.constants;

/**
 * Supported federation catalog types.
 */
public final class FederationCatalogTypes {

  public static final String VIRTUAL = "VIRTUAL";

  public static final String DATA_SOURCE = "DATA_SOURCE";

  private FederationCatalogTypes() {
  }

  /**
   * Normalizes a catalog type and defaults blank values to virtual catalogs.
   *
   * @param value raw catalog type
   * @return normalized catalog type
   */
  public static String normalize(final String value) {
    if (value == null || value.isBlank()) {
      return VIRTUAL;
    }
    return value.trim().toUpperCase(java.util.Locale.ROOT);
  }

  /**
   * Returns whether the normalized value represents a virtual catalog.
   *
   * @param value raw catalog type
   * @return true when virtual
   */
  public static boolean isVirtual(final String value) {
    return VIRTUAL.equals(normalize(value));
  }

  /**
   * Returns whether the normalized value represents an auto-generated datasource catalog.
   *
   * @param value raw catalog type
   * @return true when datasource catalog
   */
  public static boolean isDataSource(final String value) {
    return DATA_SOURCE.equals(normalize(value));
  }
}
