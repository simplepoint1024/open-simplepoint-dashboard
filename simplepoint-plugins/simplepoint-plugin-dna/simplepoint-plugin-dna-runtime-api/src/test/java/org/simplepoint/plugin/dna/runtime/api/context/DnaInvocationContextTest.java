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
  @Test
  void relatedPoliciesAreBoundedImmutableAndPreserveLegacyJson() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var legacy = context(Map.of());
    String json = mapper.writeValueAsString(legacy);
    assertThat(json).doesNotContain("additionalResourcePolicies");
    assertThat(mapper.readValue(json, DnaInvocationContext.class)).isEqualTo(legacy);
    var fields = new java.util.HashMap<String, String>(); fields.put("model#name", " masked ");
    var related = new DnaResourcePolicy("1", " self ", Set.of(), false, List.of(), fields);
    var map = new java.util.HashMap<String,DnaResourcePolicy>(); map.put(" target ", related);
    var context = context(map); fields.clear(); map.clear();
    assertThat(context.additionalResourcePolicies()).containsOnlyKeys("target");
    assertThat(context.additionalResourcePolicies().get("target").fieldPermissions()).containsEntry("model#name", "MASKED");
    assertThat(mapper.readValue(mapper.writeValueAsString(context), DnaInvocationContext.class)).isEqualTo(context);
    assertThatThrownBy(() -> context.additionalResourcePolicies().clear()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void relatedPolicyCannotOverrideSourceOrIntroduceAnUngrantedOrAmbiguousEntry() {
    var policy = new DnaResourcePolicy("1", "SELF", Set.of(), false, List.of(), Map.of());
    for (var policies : List.of(Map.of("source", policy), Map.of("not-granted", policy), Map.of("target", policy, " target ", policy))) {
      assertThatThrownBy(() -> context(policies)).isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new DnaResourcePolicy("2", "ALL", Set.of(), false, List.of(), Map.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DnaResourcePolicy("1", null, Set.of(), false, List.of(), Map.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DnaResourcePolicy("1", "ALL", Set.of(), false, List.of(), Map.of("field", "HIDDEN", " field ", "VISIBLE")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void relatedPoliciesHaveAnAggregateBudgetInAdditionToPerPageBounds() {
    var fields = new java.util.HashMap<String,String>();
    for (int i=0;i<512;i++) fields.put("field"+i,"HIDDEN");
    var policy = new DnaResourcePolicy("1","SELF",Set.of(),false,List.of(),fields);
    var policies = new java.util.HashMap<String,DnaResourcePolicy>();
    for(int i=0;i<9;i++) policies.put("resource"+i,policy);
    assertThatThrownBy(() -> new DnaInvocationContext("tenant","actor",null,"source",true,Set.of(),"SELF",Set.of(),false,List.of(),Map.of(),policies))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("aggregate budget");
    for(int i=9;i<51;i++) policies.put("resource"+i,policy);
    assertThatThrownBy(() -> new DnaInvocationContext("tenant","actor",null,"source",true,Set.of(),"SELF",Set.of(),false,List.of(),Map.of(),policies))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("policy budget");
  }

  private static DnaInvocationContext context(Map<String,DnaResourcePolicy> related) {
    return new DnaInvocationContext("tenant","actor",null,"source",false,Set.of("source","target"),"ALL",Set.of(),false,List.of(),Map.of(),related);
  }

}
