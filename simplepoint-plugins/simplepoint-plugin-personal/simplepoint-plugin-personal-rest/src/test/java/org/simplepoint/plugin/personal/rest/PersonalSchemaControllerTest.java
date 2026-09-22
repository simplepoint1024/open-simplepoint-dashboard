package org.simplepoint.plugin.personal.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.api.security.generator.JsonSchemaGenerator;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.api.security.service.JsonSchemaDetailsService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.data.json.schema.configuration.JsonSchemaAutoConfiguration;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PersonalSchemaControllerTest {
  private MockMvc mvc;
  private AuthorizationContext authorization;

  @BeforeEach
  void setup() {
    var config = new JsonSchemaAutoConfiguration(new ObjectMapper());
    var generator =
        config.defaultJsonSchemaGenerator(
            config.schemaGenerator(
                Set.of(
                    config.openApiModule(),
                    config.jacksonModule(),
                    config.javaxValidationModule())));
    var details = mock(DetailsProviderService.class);
    when(details.getDialect(JsonSchemaDetailsService.class))
        .thenReturn(mock(JsonSchemaDetailsService.class));
    when(details.getDialect(JsonSchemaGenerator.class)).thenReturn(generator);
    authorization = new AuthorizationContext();
    authorization.setUserId("ordinary-user");
    authorization.setScopeType(AuthorizationScopeType.TENANT);
    authorization.setIsAdministrator(false);
    authorization.setResources(
        List.of("personal.items.add", "personal.items.edit", "personal.items.detail"));
    mvc =
        MockMvcBuilders.standaloneSetup(new PersonalSchemaController(details))
            .setMessageConverters(new JacksonJsonHttpMessageConverter())
            .defaultRequest(
                get("/").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authorization))
            .build();
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @Test
  void itemSchemaUsesActualPlatformGeneratorAndPermissionFilteredButtons() throws Exception {
    mvc.perform(get("/personal/items/schema"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.schema.properties.title.title").value("i18n:personal.title"))
        .andExpect(jsonPath("$.schema.properties.title.maxLength").value(200))
        .andExpect(jsonPath("$.schema.properties.dueAt.format").value("date-time"))
        .andExpect(jsonPath("$.schema.properties.priority.oneOf.length()").value(4))
        .andExpect(jsonPath("$.schema.properties.priority.default").value(1))
        .andExpect(jsonPath("$.schema.properties.status.oneOf.length()").value(4))
        .andExpect(jsonPath("$.buttons.length()").value(3))
        .andExpect(jsonPath("$.buttons[0].key").value("add"))
        .andExpect(jsonPath("$.buttons[1].argumentMinSize").value(1))
        .andExpect(jsonPath("$.buttons[1].argumentMaxSize").value(1))
        .andExpect(jsonPath("$.schema.properties.ownerId").doesNotExist())
        .andExpect(jsonPath("$.schema.properties.workspace").doesNotExist())
        .andExpect(jsonPath("$.schema.properties.version").doesNotExist());
  }

  @Test
  void todoAndMessageSchemasDoNotInventButtonsForMissingPermissions() throws Exception {
    authorization = new AuthorizationContext();
    authorization.setResources(List.of("personal.todos.add", "personal.messages.readAll"));
    mvc.perform(
            get("/personal/todos/schema")
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authorization))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.buttons.length()").value(1))
        .andExpect(jsonPath("$.buttons[0].key").value("add"));
    mvc.perform(
            get("/personal/messages/schema")
                .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, authorization))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.buttons.length()").value(1))
        .andExpect(jsonPath("$.buttons[0].key").value("readAll"))
        .andExpect(jsonPath("$.schema.properties.sourceKey").doesNotExist());
    mvc.perform(
            get("/personal/items/schema")
                .requestAttr(
                    RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, new AuthorizationContext()))
        .andExpect(jsonPath("$.buttons").isEmpty());
  }

  @Test
  void productionSerializationExportsBrowserContractsWithoutJacksonNodeMetadata() throws Exception {
    var context = new AuthorizationContext();
    var authorities = new java.util.ArrayList<String>();
    for (var type :
        List.of(
            org.simplepoint.plugin.personal.api.PersonalTableModels.ItemTable.class,
            org.simplepoint.plugin.personal.api.PersonalTableModels.TodoTable.class,
            org.simplepoint.plugin.personal.api.PersonalTableModels.MessageTable.class)) {
      for (var button :
          type.getAnnotation(org.simplepoint.core.annotation.ButtonDeclarations.class).value()) {
        authorities.add(button.authority());
      }
    }
    context.setResources(authorities);
    var mapper = new ObjectMapper();
    Map<String, Object> contracts = new LinkedHashMap<>();
    for (var kind : List.of("items", "todos", "messages")) {
      var json =
          mvc.perform(
                  get("/personal/" + kind + "/schema")
                      .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.schema.type").value("object"))
              .andExpect(jsonPath("$.schema.properties.title.title").value("i18n:personal.title"))
              .andExpect(jsonPath("$.schema.nodeType").doesNotExist())
              .andReturn()
              .getResponse()
              .getContentAsString();
      contracts.put(kind, mapper.readTree(json));
    }
    var target = Path.of("build/generated-test-resources/personal-schemas.json");
    Files.createDirectories(target.getParent());
    mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), contracts);
    // Explicit fixture regeneration mode exports real HTTP JSON; normal CI still compares exactly.
    if ("true".equals(System.getenv("EXPORT_PERSONAL_SCHEMA"))) return;
    var browserFixture =
        Path.of("../../../simplepoint-react/scripts/personal-schemas.fixture.json");
    assertThat(mapper.readTree(browserFixture.toFile()))
        .as("Browser schemas must match the production-serialized backend contract")
        .isEqualTo(mapper.valueToTree(contracts));
  }

  @Test
  void allActionAuthoritiesAreUniqueAndSingleRecordMutationsAreBounded() {
    Set<String> authorities = new java.util.HashSet<>();
    for (var type :
        List.of(
            org.simplepoint.plugin.personal.api.PersonalTableModels.ItemTable.class,
            org.simplepoint.plugin.personal.api.PersonalTableModels.TodoTable.class,
            org.simplepoint.plugin.personal.api.PersonalTableModels.MessageTable.class)) {
      for (var button :
          type.getAnnotation(org.simplepoint.core.annotation.ButtonDeclarations.class).value()) {
        assertThat(authorities.add(button.authority())).isTrue();
        assertThat(button.argumentMaxSize()).isLessThanOrEqualTo(1);
        if (!Set.of("add", "readAll").contains(button.key())) {
          assertThat(button.argumentMinSize()).isEqualTo(1);
        }
      }
    }
  }
}
