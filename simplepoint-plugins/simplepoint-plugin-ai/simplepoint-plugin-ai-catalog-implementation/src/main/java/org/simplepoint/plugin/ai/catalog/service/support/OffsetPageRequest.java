package org.simplepoint.plugin.ai.catalog.service.support;

import java.io.Serial;
import java.io.Serializable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Pageable with an arbitrary row offset for mixed-source catalog pages.
 */
public final class OffsetPageRequest implements Pageable, Serializable {

  @Serial
  private static final long serialVersionUID = 1L;

  private final long offset;

  private final int pageSize;

  /**
   * Creates a bounded arbitrary-offset request.
   */
  public OffsetPageRequest(final long offset, final int pageSize) {
    if (offset < 0 || pageSize < 1) {
      throw new IllegalArgumentException("Offset and page size must be positive");
    }
    this.offset = offset;
    this.pageSize = pageSize;
  }

  @Override
  public int getPageNumber() {
    return Math.toIntExact(offset / pageSize);
  }

  @Override
  public int getPageSize() {
    return pageSize;
  }

  @Override
  public long getOffset() {
    return offset;
  }

  @Override
  public Sort getSort() {
    return Sort.unsorted();
  }

  @Override
  public Pageable next() {
    return new OffsetPageRequest(offset + pageSize, pageSize);
  }

  @Override
  public Pageable previousOrFirst() {
    return hasPrevious()
        ? new OffsetPageRequest(Math.max(0, offset - pageSize), pageSize)
        : first();
  }

  @Override
  public Pageable first() {
    return new OffsetPageRequest(0, pageSize);
  }

  @Override
  public Pageable withPage(final int pageNumber) {
    if (pageNumber < 0) {
      throw new IllegalArgumentException("Page number must not be negative");
    }
    return new OffsetPageRequest((long) pageNumber * pageSize, pageSize);
  }

  @Override
  public boolean hasPrevious() {
    return offset > 0;
  }
}
