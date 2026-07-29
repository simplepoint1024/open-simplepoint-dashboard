package org.simplepoint.plugin.ai.runtime.rest.controller;

import org.simplepoint.plugin.ai.runtime.api.constants.AiRuntimePaths;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeControlResponse;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeFencedException;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeHeartbeat;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeNotRegisteredException;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeOfflineRequest;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeNodeRegistration;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeNodeService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Private node registration and heartbeat API consumed by Tool Runtime processes.
 */
@RestController
@RequestMapping(AiRuntimePaths.INTERNAL_NODES)
@PreAuthorize("hasRole('TOOL_RUNTIME_NODE')")
public class AiRuntimeNodeInternalController {

  private final AiRuntimeNodeService service;

  /**
   * Creates the internal node controller.
   */
  public AiRuntimeNodeInternalController(final AiRuntimeNodeService service) {
    this.service = service;
  }

  /**
   * Registers or replaces one node process generation.
   */
  @PostMapping("/{nodeId}/registration")
  public RuntimeNodeControlResponse register(
      @PathVariable("nodeId") final String nodeId,
      @RequestBody final RuntimeNodeRegistration registration
  ) {
    try {
      return service.register(nodeId, registration);
    } catch (IllegalArgumentException ex) {
      throw badRequest(ex);
    }
  }

  /**
   * Renews one matching node process generation.
   */
  @PostMapping("/{nodeId}/heartbeat")
  public RuntimeNodeControlResponse heartbeat(
      @PathVariable("nodeId") final String nodeId,
      @RequestBody final RuntimeNodeHeartbeat heartbeat
  ) {
    try {
      return service.heartbeat(nodeId, heartbeat);
    } catch (RuntimeNodeNotRegisteredException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
    } catch (RuntimeNodeFencedException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
    } catch (IllegalArgumentException ex) {
      throw badRequest(ex);
    }
  }

  /**
   * Marks one matching process generation offline during graceful shutdown.
   */
  @PostMapping("/{nodeId}/offline")
  public RuntimeNodeControlResponse offline(
      @PathVariable("nodeId") final String nodeId,
      @RequestBody final RuntimeNodeOfflineRequest request
  ) {
    try {
      return service.offline(nodeId, request);
    } catch (RuntimeNodeNotRegisteredException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
    } catch (RuntimeNodeFencedException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
    } catch (IllegalArgumentException ex) {
      throw badRequest(ex);
    }
  }

  private ResponseStatusException badRequest(final IllegalArgumentException ex) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
  }
}
