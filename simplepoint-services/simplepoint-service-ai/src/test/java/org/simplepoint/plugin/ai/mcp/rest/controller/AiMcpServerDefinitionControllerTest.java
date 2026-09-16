package org.simplepoint.plugin.ai.mcp.rest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpServerDefinitionService;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class AiMcpServerDefinitionControllerTest {

  @Test
  void workbenchPermissionsReflectEachGrantedOperation() {
    var authentication = new UsernamePasswordAuthenticationToken(
        "user",
        "ignored",
        List.of(
            new SimpleGrantedAuthority("ai.workbench.tools.call"),
            new SimpleGrantedAuthority("ai.workbench.mcp-servers.get-prompt")
        )
    );
    var controller = new AiMcpServerDefinitionController(
        mock(AiMcpServerDefinitionService.class)
    );

    var response = controller.workbenchPermissions(authentication);

    assertThat(response.getBody()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("callTool", true)
        .containsEntry("readResource", false)
        .containsEntry("getPrompt", true)
        .containsEntry("viewCapabilities", true)
        .containsEntry("viewServers", false);
  }

  @Test
  void administratorCanUseEveryCapabilityOperation() {
    var authentication = new UsernamePasswordAuthenticationToken(
        "admin",
        "ignored",
        List.of(new SimpleGrantedAuthority("ROLE_Administrator"))
    );
    var controller = new AiMcpServerDefinitionController(
        mock(AiMcpServerDefinitionService.class)
    );

    var response = controller.workbenchPermissions(authentication);

    assertThat(response.getBody()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .allSatisfy((ignored, value) -> assertThat(value).isEqualTo(true));
  }

  @Test
  void publicationActionMakesTheConsolidatedPublicationTabVisible() {
    var authentication = new UsernamePasswordAuthenticationToken(
        "publisher",
        "ignored",
        List.of(new SimpleGrantedAuthority("ai.workbench.mcp-publications.create"))
    );
    var controller = new AiMcpServerDefinitionController(
        mock(AiMcpServerDefinitionService.class)
    );

    var response = controller.workbenchPermissions(authentication);

    assertThat(response.getBody()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("viewPublications", true)
        .containsEntry("viewServers", false);
  }

  @Test
  void runtimePermissionsKeepReadAndManagementCapabilitiesSeparate() {
    var authentication = new UsernamePasswordAuthenticationToken(
        "runtime-operator",
        "ignored",
        List.of(new SimpleGrantedAuthority("ai.workbench.runtime.workloads.manage"))
    );
    var controller = new AiMcpServerDefinitionController(
        mock(AiMcpServerDefinitionService.class)
    );

    var response = controller.workbenchPermissions(authentication);

    assertThat(response.getBody()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("viewRuntime", true)
        .containsEntry("manageWorkloads", true)
        .containsEntry("managePools", false)
        .containsEntry("manageSecrets", false)
        .containsEntry("viewNodes", false);
  }

  @Test
  void capabilityReadsAcceptEveryConsolidatedCapabilityPermission() throws Exception {
    List<Method> methods = List.of(
        AiMcpServerDefinitionController.class.getMethod("tools", String.class),
        AiMcpServerDefinitionController.class.getMethod("resources", String.class),
        AiMcpServerDefinitionController.class.getMethod("resourceTemplates", String.class),
        AiMcpServerDefinitionController.class.getMethod("prompts", String.class),
        AiMcpServerDefinitionController.class.getMethod(
            "snapshots",
            String.class,
            Pageable.class
        ),
        AiMcpServerDefinitionController.class.getMethod(
            "snapshot",
            String.class,
            String.class
        )
    );

    methods.forEach(method -> {
      PreAuthorize authorization = method.getAnnotation(PreAuthorize.class);
      assertThat(authorization).as(method.getName()).isNotNull();
      assertThat(authorization.value())
          .contains("ai.workbench.mcp-servers.view")
          .contains("ai.workbench.tools.view")
          .contains("ai.workbench.tools.call")
          .contains("ai.workbench.mcp-servers.read-resource")
          .contains("ai.workbench.mcp-servers.get-prompt");
    });
  }
}
