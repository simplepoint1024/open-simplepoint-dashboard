package org.simplepoint.plugin.ai.core.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.ai.core.api.constants.AiPaths;
import org.simplepoint.plugin.ai.core.api.service.AiBillingQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Scope-isolated model billing overview endpoints. */
@RestController
@RequestMapping({AiPaths.PLATFORM_BILLING, AiPaths.TENANT_BILLING})
@Tag(name = "AI模型计费", description = "按作用域和币种汇总模型调用费用")
public class AiBillingController {

  private final AiBillingQueryService billingQueryService;

  /** Creates the billing controller. */
  public AiBillingController(final AiBillingQueryService billingQueryService) {
    this.billingQueryService = billingQueryService;
  }

  /** Returns the current UTC month by default and accepts a maximum range of 366 days. */
  @GetMapping("/summary")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAnyAuthority('ai.system.billing.view', 'ai.billing.view')"
  )
  @Operation(summary = "查询模型计费汇总")
  public Response<?> summary(
      @RequestParam(name = "from", required = false)
      @DateTimeFormat(iso = ISO.DATE_TIME) final Instant from,
      @RequestParam(name = "to", required = false)
      @DateTimeFormat(iso = ISO.DATE_TIME) final Instant to
  ) {
    try {
      return Response.okay(billingQueryService.summarize(from, to));
    } catch (IllegalArgumentException ex) {
      return Response.of(
          ResponseEntity.badRequest()
              .contentType(MediaType.TEXT_PLAIN)
              .body(ex.getMessage())
      );
    }
  }
}
