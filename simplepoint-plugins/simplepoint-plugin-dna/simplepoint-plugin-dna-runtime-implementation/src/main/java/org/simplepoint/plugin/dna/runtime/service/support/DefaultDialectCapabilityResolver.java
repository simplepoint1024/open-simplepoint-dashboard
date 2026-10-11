package org.simplepoint.plugin.dna.runtime.service.support;

import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.dialect.DialectCapabilityDescriptor;
import org.simplepoint.plugin.dna.runtime.api.dialect.DialectCapabilityProvider;
import org.simplepoint.plugin.dna.runtime.api.dialect.DialectCapabilityResolver;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.springframework.stereotype.Component;

/** Rejects unknown or ambiguous database capability providers before planning. */
@Component
public class DefaultDialectCapabilityResolver implements DialectCapabilityResolver {

  private final List<DialectCapabilityProvider> providers;

  /** Creates a resolver from all installed dialect capability providers. */
  public DefaultDialectCapabilityResolver(
      final List<DialectCapabilityProvider> providers
  ) {
    this.providers = List.copyOf(providers);
  }

  @Override
  public DialectCapabilityDescriptor resolve(final DatabaseIdentity database) {
    List<DialectCapabilityProvider> matching = providers.stream()
        .filter(provider -> provider.supports(database))
        .toList();
    if (matching.size() != 1) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.DIALECT_CAPABILITY_UNKNOWN,
          "Expected one capability provider for " + database.productName()
              + " but found " + matching.size()
      );
    }
    DialectCapabilityDescriptor descriptor = matching.getFirst().describe(database);
    if (descriptor == null) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.DIALECT_CAPABILITY_UNKNOWN,
          "Capability provider returned no descriptor for " + database.productName()
      );
    }
    return descriptor;
  }
}
