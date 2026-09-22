package org.simplepoint.core.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Serializable;
import org.junit.jupiter.api.Test;

class QueryCapabilitiesTest {
  static class GenericEntity<I extends Serializable> {
    I id;
  }

  static class Intermediate<I extends Serializable> extends GenericEntity<I> {}

  @TableQuery({@QueryField("id")})
  static class TextEntity extends Intermediate<String> {}

  @TableQuery({@QueryField("id")})
  static class NumericEntity extends Intermediate<Long> {}

  @Test
  void resolvesInheritedGenericFieldsForSchemaAndOperators() {
    var mapper = new ObjectMapper();
    var text = mapper.createObjectNode();
    QueryCapabilities.applySchema(TextEntity.class, text);
    assertThat(text.at("/x-query/fields/id/javaType").asText()).isEqualTo("String");
    assertThat(
            QueryCapabilities.operators(
                TextEntity.class, QueryCapabilities.declaration(TextEntity.class).value()[0]))
        .contains("like", "equals");
    var number = mapper.createObjectNode();
    QueryCapabilities.applySchema(NumericEntity.class, number);
    assertThat(number.at("/x-query/fields/id/javaType").asText()).isEqualTo("Long");
    assertThat(
            QueryCapabilities.operators(
                NumericEntity.class, QueryCapabilities.declaration(NumericEntity.class).value()[0]))
        .contains("equals", "between")
        .doesNotContain("like");
  }
}
