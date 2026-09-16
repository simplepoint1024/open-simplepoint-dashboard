package org.simplepoint.plugin.ai.core.rest.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.model.AiWorkbenchErrorCode;
import org.simplepoint.plugin.ai.core.api.service.AiBillingQueryService;
import org.simplepoint.plugin.ai.core.api.vo.AiBillingModels.BillingSummary;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiBillingControllerTest {

  private AiBillingQueryService billingQueryService;

  private AiBillingController controller;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    billingQueryService = mock(AiBillingQueryService.class);
    controller = new AiBillingController(billingQueryService);
    mockMvc = MockMvcBuilders
        .standaloneSetup(controller)
        .build();
  }

  @Test
  void summaryBindsExplicitTimeRangeParameterNames() throws Exception {
    Instant from = Instant.parse("2026-07-01T00:00:00Z");
    Instant to = Instant.parse("2026-08-01T00:00:00Z");
    when(billingQueryService.summarize(from, to))
        .thenReturn(new BillingSummary(from, to, 0, 0, 0, List.of(), List.of()));

    mockMvc.perform(get("/workbench/billing/summary")
            .param("from", from.toString())
            .param("to", to.toString()))
        .andExpect(status().isOk());

    verify(billingQueryService).summarize(from, to);
  }

  @Test
  void summaryAllowsDefaultTimeRange() throws Exception {
    when(billingQueryService.summarize(null, null))
        .thenReturn(new BillingSummary(null, null, 0, 0, 0, List.of(), List.of()));

    mockMvc.perform(get("/workbench/billing/summary"))
        .andExpect(status().isOk());

    verify(billingQueryService).summarize(null, null);
  }

  @Test
  void invalidRangeReturnsStableJsonWithoutPrivateProse() throws Exception {
    String privateProse = "internal retention-window configuration";
    when(billingQueryService.summarize(null, null))
        .thenThrow(new IllegalArgumentException(privateProse));

    mockMvc.perform(get("/workbench/billing/summary"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.errorCode").value(
            AiWorkbenchErrorCode.AI_BILLING_RANGE_INVALID.name()
        ))
        .andExpect(content().string(not(containsString(privateProse))));
  }

  @Test
  void unexpectedQueryFailureReturnsStableServerError() throws Exception {
    String privateProse = "billing database topology and SQL details";
    when(billingQueryService.summarize(null, null))
        .thenThrow(new RuntimeException(privateProse));

    mockMvc.perform(get("/workbench/billing/summary"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.errorCode").value(
            AiWorkbenchErrorCode.AI_BILLING_QUERY_FAILED.name()
        ))
        .andExpect(content().string(not(containsString(privateProse))));
  }

  @Test
  void accessDeniedRemainsAccessDenied() {
    when(billingQueryService.summarize(null, null))
        .thenThrow(new AccessDeniedException("denied"));

    assertThrows(
        AccessDeniedException.class,
        () -> controller.summary(null, null)
    );
  }
}
