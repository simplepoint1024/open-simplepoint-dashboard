package org.simplepoint.plugin.rbac.tenant.service.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.simplepoint.api.security.generator.JsonSchemaGenerator;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.api.security.service.JsonSchemaDetailsService;

/** Provides the JSON-schema collaborators required by base CRUD modification tests. */
public final class BaseServiceSchemaTestSupport {

  private BaseServiceSchemaTestSupport() {
  }

  /**
   * Configures a minimal schema provider while leaving entity-specific validation to the service test.
   *
   * @param detailsProviderService mocked details provider
   */
  public static void stubBaseServiceSchema(final DetailsProviderService detailsProviderService) {
    JsonSchemaDetailsService formSchema = mock(JsonSchemaDetailsService.class);
    JsonSchemaGenerator generator = mock(JsonSchemaGenerator.class);
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode schema = mapper.createObjectNode();
    schema.set("properties", mapper.createObjectNode());
    lenient().when(detailsProviderService.getDialect(JsonSchemaDetailsService.class)).thenReturn(formSchema);
    lenient().when(detailsProviderService.getDialect(JsonSchemaGenerator.class)).thenReturn(generator);
    lenient().when(generator.generateSchema(any())).thenReturn(schema);
    lenient().when(detailsProviderService.getDialects(any())).thenReturn(List.of());
  }
}
