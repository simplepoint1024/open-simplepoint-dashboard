package org.simplepoint.plugin.dna.federation.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.dna.federation.api.constants.DnaFederationPaths;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryAudit;
import org.simplepoint.plugin.dna.federation.api.service.FederationQueryAuditService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Federation query audit endpoints.
 */
@RestController
@RequestMapping({DnaFederationPaths.QUERY_AUDITS, DnaFederationPaths.PLATFORM_QUERY_AUDITS})
@Tag(name = "查询审计管理", description = "用于查看联邦查询审计记录")
public class FederationQueryAuditController
    extends BaseController<FederationQueryAuditService, FederationQueryAudit, String> {

  /**
   * Creates a federation query audit controller.
   *
   * @param service query audit service
   */
  public FederationQueryAuditController(final FederationQueryAuditService service) {
    super(service);
  }

  /**
   * Pages federation query audits.
   *
   * @param attributes filter attributes
   * @param pageable   paging arguments
   * @return paged query audits
   */
  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.query-audits.view')")
  @Operation(summary = "分页查询查询审计", description = "根据条件分页查询联邦查询审计记录")
  public Response<Page<FederationQueryAudit>> limit(
      @RequestParam final Map<String, String> attributes,
      final Pageable pageable
  ) {
    return limit(service.limit(attributes, pageable), FederationQueryAudit.class);
  }
}
