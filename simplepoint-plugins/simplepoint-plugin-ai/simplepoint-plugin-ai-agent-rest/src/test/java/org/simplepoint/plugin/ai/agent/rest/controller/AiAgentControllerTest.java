package org.simplepoint.plugin.ai.agent.rest.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiAgentControllerTest {

  private AiAgentExecutionService executionService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    executionService = mock(AiAgentExecutionService.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new AiAgentController(
        mock(AiAgentService.class),
        executionService,
        mock(AiAgentMemoryService.class)
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
}
