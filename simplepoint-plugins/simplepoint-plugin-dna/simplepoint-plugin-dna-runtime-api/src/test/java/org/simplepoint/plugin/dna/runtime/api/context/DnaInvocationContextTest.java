package org.simplepoint.plugin.dna.runtime.api.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DnaInvocationContextTest {

  @Test
  void canonicalizesBoundedPolicyValues() {
    DnaInvocationContext context = new DnaInvocationContext(
        " tenant-a ",
        " user-a ",
        " dept-a ",
        " app.forms.orders ",
        false,
        Set.of(" app.forms.orders "),
        " self ",
        Set.of(" dept-a "),
        true,
        List.of(" owner == current.userId "),
        Map.of("dna.runtime.record.model-1#title", " editable ")
    );

    assertThat(context.tenantId()).isEqualTo("tenant-a");
    assertThat(context.dataScopeType()).isEqualTo("SELF");
    assertThat(context.resources()).containsExactly("app.forms.orders");
    assertThat(context.fieldPermissions()).containsEntry(
        "dna.runtime.record.model-1#title",
        "EDITABLE"
    );
  }

  @Test
  void rejectsUnboundedOrUnknownPolicyValues() {
    assertThatThrownBy(() -> new DnaInvocationContext(
        "tenant-a",
        "user-a",
        null,
        "resource",
        false,
        Set.of(),
        "UNKNOWN",
        Set.of(),
        false,
        List.of(),
        Map.of()
    )).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DnaInvocationContext(
        "tenant-a",
        "user-a",
        null,
        "resource",
        false,
        Set.of(),
        "ALL",
        Set.of(),
        false,
        List.of(),
        Map.of("model#field", "OWNER")
    )).isInstanceOf(IllegalArgumentException.class);
  }
}
