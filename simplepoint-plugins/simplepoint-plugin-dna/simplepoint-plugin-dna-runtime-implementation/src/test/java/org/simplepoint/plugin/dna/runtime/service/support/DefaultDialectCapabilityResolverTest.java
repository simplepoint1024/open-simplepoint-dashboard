package org.simplepoint.plugin.dna.runtime.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.dialect.DialectCapabilityDescriptor;
import org.simplepoint.plugin.dna.runtime.api.dialect.DialectCapabilityProvider;
import org.simplepoint.plugin.dna.runtime.api.dialect.DialectSupportTier;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;

class DefaultDialectCapabilityResolverTest {

  private static final DatabaseIdentity DATABASE =
      new DatabaseIdentity("ExampleDB", "1.0", 1, 0);

  @Test
  void requiresExactlyOneVersionAwareProvider() {
    DefaultDialectCapabilityResolver empty = new DefaultDialectCapabilityResolver(List.of());
    assertThatThrownBy(() -> empty.resolve(DATABASE))
        .isInstanceOfSatisfying(DnaRuntimeException.class, exception ->
            assertThat(exception.errorCode())
                .isEqualTo(DnaRuntimeErrorCode.DIALECT_CAPABILITY_UNKNOWN));

    DialectCapabilityProvider provider = provider("example");
    DialectCapabilityDescriptor descriptor =
        new DefaultDialectCapabilityResolver(List.of(provider)).resolve(DATABASE);
    assertThat(descriptor.dialectId()).isEqualTo("example");

    DefaultDialectCapabilityResolver ambiguous =
        new DefaultDialectCapabilityResolver(List.of(provider, provider("duplicate")));
    assertThatThrownBy(() -> ambiguous.resolve(DATABASE))
        .isInstanceOf(DnaRuntimeException.class);
  }

  private static DialectCapabilityProvider provider(final String dialectId) {
    return new DialectCapabilityProvider() {
      @Override
      public boolean supports(final DatabaseIdentity database) {
        return true;
      }

      @Override
      public DialectCapabilityDescriptor describe(final DatabaseIdentity database) {
        return new DialectCapabilityDescriptor(
            dialectId,
            database,
            DialectSupportTier.EXPERIMENTAL,
            Map.of()
        );
      }
    };
  }
}
