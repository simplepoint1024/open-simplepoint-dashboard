package org.simplepoint.core.query;

/** Shared limits for browser data tables. Lookup directories and transport cursors remain independent. */
public final class TablePagination {
  /** Initial page size for browser table controllers. */
  public static final int DEFAULT_PAGE_SIZE = 50;
  /** Upper row budget advertised by ordinary table query schemas. */
  public static final int MAX_PAGE_SIZE = 500;

  private TablePagination() {}
}
