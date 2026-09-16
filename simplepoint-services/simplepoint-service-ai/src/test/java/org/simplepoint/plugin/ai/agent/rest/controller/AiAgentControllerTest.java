package org.simplepoint.plugin.ai.agent.rest.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionEventFeed;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionMetrics;
import org.simplepoint.plugin.ai.agent.api.model.AgentExecutionStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceStatus;
import org.simplepoint.plugin.ai.agent.api.model.AgentTraceType;
import org.simplepoint.plugin.ai.agent.api.service.AiAgentExecutionService;
import org.simplepoint.plugin.ai.agent.api.service.AiAgentMemoryService;
import org.simplepoint.plugin.ai.agent.api.service.AiAgentService;
import org.simplepoint.plugin.ai.core.api.model.AiDependencyKind;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiAgentControllerTest {

  private AiAgentExecutionService executionService;

  private AiAgentService agentService;

  private AiAgentMemoryService memoryService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    executionService = mock(AiAgentExecutionService.class);
    agentService = mock(AiAgentService.class);
    memoryService = mock(AiAgentMemoryService.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new AiAgentController(
        agentService,
        executionService,
        memoryService
    )).setCustomArgumentResolvers(
        new PageableHandlerMethodArgumentResolver()
    ).build();
  }

  @Test
  void bindsTraceEventAndMetricQueryParameters() throws Exception {
    Instant from = Instant.parse("2026-07-29T00:00:00Z");
    Instant to = Instant.parse("2026-07-30T00:00:00Z");
    when(executionService.findTraces(
        eq("agent-a"),
        eq("execution-a"),
        eq(AgentTraceType.MODEL),
        eq(AgentTraceStatus.SUCCEEDED),
        any()
    )).thenReturn(new PageImpl<>(
        List.of(),
        PageRequest.of(0, 10),
        0
    ));
    when(executionService.findEvents(
        "agent-a",
        "execution-a",
        3,
        25
    )).thenReturn(new AgentExecutionEventFeed(
        3,
        3,
        false,
        AgentExecutionStatus.SUCCEEDED,
        List.of()
    ));
    when(executionService.metrics("agent-a", from, to))
        .thenReturn(new AgentExecutionMetrics(
            from,
            to,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            BigDecimal.ZERO,
            0,
            0,
            List.of(),
            List.of()
        ));

    mockMvc.perform(get(
            "/workbench/agents/agent-a/executions/execution-a/traces")
            .param("type", "MODEL")
            .param("status", "SUCCEEDED")
            .param("page", "0")
            .param("size", "10"))
        .andExpect(status().isOk());
    mockMvc.perform(get(
            "/workbench/agents/agent-a/executions/execution-a/events")
            .param("after", "3")
            .param("limit", "25"))
        .andExpect(status().isOk());
    mockMvc.perform(get("/workbench/agents/agent-a/metrics")
            .param("from", from.toString())
            .param("to", to.toString()))
        .andExpect(status().isOk());

    verify(executionService).findEvents(
        "agent-a",
        "execution-a",
        3,
        25
    );
    verify(executionService).metrics("agent-a", from, to);
  }

  @Test
  void deleteOperationsReturnAnEmptySuccessfulBody() throws Exception {
    mockMvc.perform(delete("/workbench/agents/agent-a"))
        .andExpect(status().isOk())
        .andExpect(content().string(""));
    mockMvc.perform(delete(
            "/workbench/agents/agent-a/memories/memory-a"))
        .andExpect(status().isOk())
        .andExpect(content().string(""));

    verify(agentService).remove("agent-a");
    verify(memoryService).removeCurrentSubjectMemory(
        "agent-a",
        "memory-a"
    );
  }

  @Test
  void bindsDependencySearchAndResolveContracts() throws Exception {
    mockMvc.perform(get("/workbench/agents/agent-a/dependency-options")
            .param("kind", "MODEL")
            .param("q", "chat")
            .param("page", "1")
            .param("size", "25"))
        .andExpect(status().isOk());
    mockMvc.perform(get(
            "/workbench/agents/agent-a/dependency-options/resolve")
            .param("kind", "SKILL")
            .param("ids", "skill-version-a,skill-version-b"))
        .andExpect(status().isOk());

    verify(agentService).findDependencyOptions(
        "agent-a",
        AiDependencyKind.MODEL,
        "chat",
        1,
        25
    );
    verify(agentService).resolveDependencyOptions(
        "agent-a",
        AiDependencyKind.SKILL,
        List.of("skill-version-a", "skill-version-b")
    );
  }

  @Test
  void invalidDependencyKindUsesStableRequestError() throws Exception {
    mockMvc.perform(get("/workbench/agents/agent-a/dependency-options")
            .param("kind", "UNKNOWN"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(
            "AI_AGENT_REQUEST_INVALID"
        ));

    verifyNoInteractions(agentService, executionService, memoryService);
  }

  @Test
  void fixedPermissionRouteReturnsViewOnlySnapshotWithoutServiceCalls()
      throws Exception {
    var authentication = new UsernamePasswordAuthenticationToken(
        "viewer",
        "ignored",
        List.of(new SimpleGrantedAuthority(
            "ai.workbench.agents.view"
        ))
    );

    mockMvc.perform(get("/workbench/agents/workbench-permissions")
            .principal(authentication))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.view").value(true))
        .andExpect(jsonPath("$.create").value(false))
        .andExpect(jsonPath("$.edit").value(false))
        .andExpect(jsonPath("$.delete").value(false))
        .andExpect(jsonPath("$.manageVersions").value(false))
        .andExpect(jsonPath("$.publish").value(false))
        .andExpect(jsonPath("$.execute").value(false))
        .andExpect(jsonPath("$.approve").value(false))
        .andExpect(jsonPath("$.control").value(false))
        .andExpect(jsonPath("$.manageMemory").value(false))
        .andExpect(jsonPath("$.intervene").value(false));

    verifyNoInteractions(agentService, executionService, memoryService);
  }

  @Test
  void invalidRequestDoesNotExposeServiceExceptionMessage() throws Exception {
    when(agentService.findAll(any(Pageable.class))).thenThrow(
        new IllegalArgumentException("private-agent-provider-detail")
    );

    mockMvc.perform(get("/workbench/agents"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value(
            "AI_AGENT_REQUEST_INVALID"
        ))
        .andExpect(content().string(not(containsString(
            "private-agent-provider-detail"
        ))));
  }
}
