package org.simplepoint.core.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.query.QueryField;
import org.simplepoint.core.query.TableQuery;

class ProjectionTableSchemaTest {
  @TableQuery(
      value = {
        @QueryField("count"),
        @QueryField("large"),
        @QueryField("amount"),
        @QueryField("enabled"),
        @QueryField("createdAt"),
        @QueryField("date"),
        @QueryField("status")
      },
      maxPageSize = 50)
  record Summary(
      long count,
      BigInteger large,
      BigDecimal amount,
      boolean enabled,
      Instant createdAt,
      LocalDate date,
      String status,
      String internalSecret) {}

  @AfterEach
  void clear() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @Test
  void exposesOnlyDeclaredScalarsWithMatchingTypesAndChoices() {
    var schema =
        new ObjectMapper()
            .valueToTree(
                ProjectionTableSchema.schema(
                    Summary.class,
                    "summary",
                    Map.of(
                        "status",
                        List.of(new ProjectionTableSchema.Choice("READY", "i18n:summary.ready")))));
    assertThat(schema.at("/schema/properties/count/type").asText()).isEqualTo("integer");
    assertThat(schema.at("/schema/properties/large/type").asText()).isEqualTo("integer");
    assertThat(schema.at("/schema/properties/amount/type").asText()).isEqualTo("number");
    assertThat(schema.at("/schema/properties/enabled/type").asText()).isEqualTo("boolean");
    assertThat(schema.at("/schema/properties/createdAt/format").asText()).isEqualTo("date-time");
    assertThat(schema.at("/schema/properties/date/format").asText()).isEqualTo("date");
    assertThat(schema.at("/schema/properties/status/oneOf/0/title").asText())
        .isEqualTo("i18n:summary.ready");
    assertThat(schema.at("/schema/properties/count/title").asText())
        .isEqualTo("i18n:summary.count");
    assertThat(schema.at("/schema/properties/internalSecret").isMissingNode()).isTrue();
    assertThat(schema.at("/schema/x-query/fields/count/javaType").asText()).isEqualTo("long");
    assertThat(schema.at("/buttons").isEmpty()).isTrue();
  }

  @Test
  void hiddenAndMaskedFieldsCannotBeQueriedAndHiddenFieldsAreNotColumns() {
    var context = new AuthorizationContext();
    context.setFieldPermissions(Map.of("Summary#amount", "HIDDEN", "Summary#status", "MASKED"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    var schema =
        new ObjectMapper()
            .valueToTree(ProjectionTableSchema.schema(Summary.class, "summary", Map.of()));
    assertThat(schema.at("/schema/properties/amount").isMissingNode()).isTrue();
    assertThat(schema.at("/schema/x-query/fields/amount").isMissingNode()).isTrue();
    assertThat(schema.at("/schema/x-query/fields/status").isMissingNode()).isTrue();
    assertThat(schema.at("/schema/properties/status/readOnly").asBoolean()).isTrue();
    assertThat(schema.at("/schema/x-query/fields/count").isMissingNode()).isFalse();
  }
}
