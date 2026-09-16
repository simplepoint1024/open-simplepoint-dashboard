package org.simplepoint.plugin.ai.runtime.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.ai.runtime.api.constants.AiRuntimePaths;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeMcpProfile;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeMcpRevision;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileImportRequest;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileImportResult;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfilePublishRequest;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileUpdateRequest;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeMcpProfileService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Management API for normalized MCP Runtime Profiles and revisions. */
@RestController
@RequestMapping(AiRuntimePaths.PROFILES)
@Tag(name = "AI Runtime MCP Profile", description = "标准 MCP 运行配置和不可变版本")
public class AiRuntimeMcpProfileController {

  private final AiRuntimeMcpProfileService service;

  /** Creates the Runtime Profile management controller. */
  public AiRuntimeMcpProfileController(
      final AiRuntimeMcpProfileService service
  ) {
    this.service = service;
  }

  /** Imports an upstream descriptor and its platform-owned Profile draft. */
  @PostMapping("/import")
  @PreAuthorize(
      "hasRole('Administrator') or "
          + "hasAuthority('ai.workbench.runtime.pools.manage')"
  )
  @Operation(summary = "导入 MCP Descriptor 和 Runtime Profile 草稿")
  public Response<RuntimeMcpProfileImportResult> importDraft(
      @RequestBody final RuntimeMcpProfileImportRequest request
  ) {
    try {
      return Response.okay(service.importDraft(request));
    } catch (IllegalArgumentException ex) {
      throw badRequest(ex);
    }
  }

  /** Pages Runtime Profiles in the active scope. */
  @GetMapping
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.runtime.view') "
          + "or hasAuthority('ai.workbench.runtime.pools.manage')"
  )
  @Operation(summary = "分页查询 MCP Runtime Profile")
  public Response<Page<AiRuntimeMcpProfile>> findAll(final Pageable pageable) {
    return Response.limit(service.findAll(pageable), AiRuntimeMcpProfile.class);
  }

  /** Returns one readable Runtime Profile. */
  @GetMapping("/{profileId}")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.runtime.view') "
          + "or hasAuthority('ai.workbench.runtime.pools.manage')"
  )
  @Operation(summary = "查询 MCP Runtime Profile")
  public Response<AiRuntimeMcpProfile> find(
      @PathVariable("profileId") final String profileId
  ) {
    try {
      return service.find(profileId)
          .map(Response::okay)
          .orElseGet(Response::nf);
    } catch (IllegalArgumentException ex) {
      throw badRequest(ex);
    }
  }

  /** Replaces the editable draft while preserving published revisions. */
  @PostMapping("/{profileId}")
  @PreAuthorize(
      "hasRole('Administrator') or "
          + "hasAuthority('ai.workbench.runtime.pools.manage')"
  )
  @Operation(summary = "更新 MCP Runtime Profile 草稿")
  public Response<AiRuntimeMcpProfile> update(
      @PathVariable("profileId") final String profileId,
      @RequestBody final RuntimeMcpProfileUpdateRequest request
  ) {
    try {
      return Response.okay(service.update(profileId, request));
    } catch (IllegalArgumentException ex) {
      throw badRequest(ex);
    }
  }

  /** Publishes the current draft as a new immutable revision. */
  @PostMapping("/{profileId}/publish")
  @PreAuthorize(
      "hasRole('Administrator') or "
          + "hasAuthority('ai.workbench.runtime.pools.manage')"
  )
  @Operation(summary = "发布 MCP Runtime Profile 版本")
  public Response<AiRuntimeMcpRevision> publish(
      @PathVariable("profileId") final String profileId,
      @RequestBody(required = false) final RuntimeMcpProfilePublishRequest request
  ) {
    try {
      return Response.okay(service.publish(profileId, request));
    } catch (IllegalArgumentException ex) {
      throw badRequest(ex);
    }
  }

  /** Lists immutable revisions for one Runtime Profile. */
  @GetMapping("/{profileId}/revisions")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.runtime.view') "
          + "or hasAuthority('ai.workbench.runtime.pools.manage')"
  )
  @Operation(summary = "查询 MCP Runtime Profile 版本")
  public Response<List<AiRuntimeMcpRevision>> findRevisions(
      @PathVariable("profileId") final String profileId
  ) {
    try {
      return Response.okay(service.findRevisions(profileId));
    } catch (IllegalArgumentException ex) {
      throw badRequest(ex);
    }
  }

  /** Rolls the active profile snapshot back to an existing revision. */
  @PostMapping("/{profileId}/revisions/{revisionId}/activate")
  @PreAuthorize(
      "hasRole('Administrator') or "
          + "hasAuthority('ai.workbench.runtime.pools.manage')"
  )
  @Operation(summary = "激活或回滚 MCP Runtime Profile 版本")
  public Response<AiRuntimeMcpProfile> activateRevision(
      @PathVariable("profileId") final String profileId,
      @PathVariable("revisionId") final String revisionId
  ) {
    try {
      return Response.okay(service.activateRevision(profileId, revisionId));
    } catch (IllegalArgumentException ex) {
      throw badRequest(ex);
    }
  }

  /** Deletes a draft that has never produced an immutable revision. */
  @DeleteMapping("/{profileId}")
  @PreAuthorize(
      "hasRole('Administrator') or "
          + "hasAuthority('ai.workbench.runtime.pools.manage')"
  )
  @Operation(summary = "删除从未发布的 MCP Runtime Profile 草稿")
  public Response<String> removeDraft(
      @PathVariable("profileId") final String profileId
  ) {
    try {
      service.removeDraft(profileId);
      return Response.okay(profileId);
    } catch (IllegalArgumentException ex) {
      throw badRequest(ex);
    }
  }

  private static ResponseStatusException badRequest(
      final IllegalArgumentException exception
  ) {
    return new ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "AI_RUNTIME_MCP_PROFILE_REQUEST_INVALID",
        exception
    );
  }
}
