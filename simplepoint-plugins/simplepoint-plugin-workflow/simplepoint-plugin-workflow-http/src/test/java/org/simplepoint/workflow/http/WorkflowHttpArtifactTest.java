package org.simplepoint.workflow.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleHttpPlan;
import org.simplepoint.platform.composition.ModuleMigrations;

class WorkflowHttpArtifactTest {
  @Test
  void domainSelectionDoesNotImplicitlyActivateItsOptionalAdapter() {
    var catalog = ModuleCatalog.discover(getClass().getClassLoader());
    var domain = catalog.plan(List.of("workflow"));
    assertTrue(ModuleHttpPlan.from(domain).namespaces().isEmpty());
    assertFalse(
        domain
            .ownerOf(org.simplepoint.workflow.rest.WorkflowDefinitionController.class)
            .isPresent());
    var http = catalog.plan(List.of("workflow-http"));
    assertEquals(
        "workflow-http",
        http.ownerOf(org.simplepoint.workflow.rest.WorkflowDefinitionController.class)
            .orElseThrow()
            .id());
    assertFalse(
        WorkflowHttpModule.class.getDeclaredAnnotation(ModuleMigrations.class).ownsSchema());
    assertThrows(
        ClassNotFoundException.class,
        () -> Class.forName("org.simplepoint.workflow.server.WorkflowApplication"));
  }

  @Test
  void movedResourcesRetainHistoricalIdentityAndHaveExactlyOneClasspathDefinition()
      throws Exception {
    var loader = getClass().getClassLoader();
    String resource = "META-INF/simplepoint/modules/workflow-http/resources.json";
    assertEquals(1, Collections.list(loader.getResources(resource)).size());
    try (var input = loader.getResourceAsStream(resource)) {
      var document = new ObjectMapper().readTree(input);
      assertEquals("workflow", document.path("module").asText());
      assertEquals("workflow.center.view", document.path("resources").get(0).path("code").asText());
    }
    for (String locale : List.of("en-US", "zh-CN")) {
      assertEquals(
          1,
          Collections.list(
                  loader.getResources(
                      "META-INF/simplepoint/modules/workflow-http/i18n/"
                          + locale
                          + "/workflow.json"))
              .size());
    }
  }
}
