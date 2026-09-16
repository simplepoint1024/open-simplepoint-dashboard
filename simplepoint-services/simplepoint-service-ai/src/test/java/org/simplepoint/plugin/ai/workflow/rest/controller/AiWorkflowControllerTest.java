package org.simplepoint.plugin.ai.workflow.rest.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.model.AiDependencyKind;
import org.simplepoint.plugin.ai.workflow.api.service.AiWorkflowExecutionService;
import org.simplepoint.plugin.ai.workflow.api.service.AiWorkflowService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiWorkflowControllerTest {

  private AiWorkflowService workflowService;

  private AiWorkflowExecutionService executionService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    workflowService = mock(AiWorkflowService.class);
    executionService = mock(AiWorkflowExecutionService.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new AiWorkflowController(
        workflowService,
        executionService
    )).setCustomArgumentResolvers(
        new PageableHandlerMethodArgumentResolver()
    ).build();
  }

  @Test
  void fixedPermissionRouteReturnsViewOnlySnapshotWithoutServiceCalls()
      throws Exception {
    var authentication = new UsernamePasswordAuthenticationToken(
        "viewer",
        "ignored",
        List.of(new SimpleGrantedAuthority(
            "ai.workbench.workflows.view"
        ))
    );

    mockMvc.perform(get("/workbench/workflows/workbench-permissions")
            .principal(authentication))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.view").value(true))
        .andExpect(jsonPath("$.create").value(false))
        .andExpect(jsonPath("$.edit").value(false))
        .andExpect(jsonPath("$.delete").value(false))
        .andExpect(jsonPath("$.manageVersions").value(false))
        .andExpect(jsonPath("$.publish").value(false))
        .andExpect(jsonPath("$.execute").value(false))
        .andExpect(jsonPath("$.intervene").value(false));

    verifyNoInteractions(workflowService, executionService);
  }

  @Test
  void deleteReturnsAnEmptySuccessfulBody() throws Exception {
    mockMvc.perform(delete("/workbench/workflows/workflow-a"))
        .andExpect(status().isOk())
        .andExpect(content().string(""));

    verify(workflowService).remove("workflow-a");
  }

  @Test
  void bindsDependencySearchAndResolveContracts() throws Exception {
    mockMvc.perform(get(
            "/workbench/workflows/workflow-a/dependency-options")
            .param("kind", "AGENT")
            .param("q", "document")
            .param("page", "2")
            .param("size", "30"))
        .andExpect(status().isOk());
    mockMvc.perform(get(
            "/workbench/workflows/workflow-a/dependency-options/resolve")
            .param("kind", "SKILL")
            .param("ids", "skill-version-a,skill-version-b"))
        .andExpect(status().isOk());

    verify(workflowService).findDependencyOptions(
        "workflow-a",
        AiDependencyKind.AGENT,
        "document",
        2,
        30
    );
    verify(workflowService).resolveDependencyOptions(
        "workflow-a",
        AiDependencyKind.SKILL,
        List.of("skill-version-a", "skill-version-b")
    );
  }

  @Test
  void invalidDependencyKindUsesStableRequestError() throws Exception {
    mockMvc.perform(get(
            "/workbench/workflows/workflow-a/dependency-options")
            .param("kind", "UNKNOWN"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(
            "AI_WORKFLOW_REQUEST_INVALID"
        ));

    verifyNoInteractions(workflowService, executionService);
  }

  @Test
  void invalidRequestDoesNotExposeServiceExceptionMessage() throws Exception {
    when(workflowService.findAll(any(Pageable.class))).thenThrow(
        new IllegalArgumentException("private-workflow-engine-detail")
    );

    mockMvc.perform(get("/workbench/workflows"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(
            "AI_WORKFLOW_REQUEST_INVALID"
        ))
        .andExpect(content().string(not(containsString(
            "private-workflow-engine-detail"
        ))));
  }
}
