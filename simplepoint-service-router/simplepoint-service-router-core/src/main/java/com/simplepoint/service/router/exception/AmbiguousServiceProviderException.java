package com.simplepoint.service.router.exception;

import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * Rejects an ambiguous capability binding instead of selecting by registration order.
 */
public final class AmbiguousServiceProviderException extends ServiceRouterException {

  private final List<String> providers;

  /**
   * Creates a configuration failure with stable provider diagnostics.
   *
   * @param service logical capability
   * @param version contract version
   * @param providers conflicting bean names or discovery service IDs
   */
  public AmbiguousServiceProviderException(
      final String service, final String version, final Collection<String> providers) {
    super("Ambiguous providers for " + service + ":" + version + ": " + new TreeSet<>(providers));
    this.providers = List.copyOf(new TreeSet<>(providers));
  }

  /**
   * Returns sorted, immutable conflicting provider identities.
   *
   * @return provider identities
   */
  public List<String> providers() {
    return providers;
  }
}
