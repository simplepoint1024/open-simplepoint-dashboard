package org.simplepoint.dna.runtime;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPlanView;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPublicationView;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelRegistrationCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceBindingCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelResourceBindingView;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelVersionCommand;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelVersionView;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelView;
import org.simplepoint.plugin.dna.runtime.api.service.PhysicalModelAccessService;
import org.simplepoint.plugin.dna.runtime.api.service.PhysicalModelService;
import org.simplepoint.plugin.dna.runtime.api.service.SchemaPlanService;
import org.simplepoint.plugin.dna.runtime.api.service.SchemaPublicationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Thin authenticated transport for APP physical model publication orchestration. */
@RestController
@RequestMapping("/internal/dna/runtime/models")
@PreAuthorize("hasRole('Administrator') or hasAuthority('dna.runtime.model-bindings.manage')")
@Tag(name = "DNA Runtime Model", description = "表单物理模型与发布")
public class RuntimeModelController {

  private final PhysicalModelService modelService;
  private final PhysicalModelAccessService accessService;
  private final SchemaPlanService planService;
  private final SchemaPublicationService publicationService;

  /** Creates the Runtime model transport adapter. */
  public RuntimeModelController(
      final PhysicalModelService modelService,
      final PhysicalModelAccessService accessService,
      final SchemaPlanService planService,
      final SchemaPublicationService publicationService
  ) {
    this.modelService = modelService;
    this.accessService = accessService;
    this.planService = planService;
    this.publicationService = publicationService;
  }

  @PostMapping
  public Response<PhysicalModelView> register(
      @RequestBody final PhysicalModelRegistrationCommand command
  ) {
    return Response.okay(modelService.register(command));
  }

  @GetMapping("/{modelId}")
  public Response<PhysicalModelView> get(@PathVariable final String modelId) {
    return Response.okay(modelService.get(modelId));
  }

  @PostMapping("/{modelId}/versions")
  public Response<PhysicalModelVersionView> createVersion(
      @PathVariable final String modelId,
      @RequestBody final PhysicalModelVersionCommand command
  ) {
    return Response.okay(modelService.createVersion(modelId, command));
  }

  @GetMapping("/{modelId}/plan")
  public Response<SchemaPlanView> plan(
      @PathVariable final String modelId,
      @RequestParam final int targetVersion
  ) {
    return Response.okay(planService.plan(modelId, targetVersion));
  }

  @PostMapping("/{modelId}/publish")
  public Response<SchemaPublicationView> publish(
      @PathVariable final String modelId,
      @RequestBody final SchemaPublicationCommand command
  ) {
    return Response.okay(publicationService.publish(modelId, command));
  }

  @PostMapping("/bindings")
  public Response<PhysicalModelResourceBindingView> bind(
      @RequestBody final PhysicalModelResourceBindingCommand command
  ) {
    return Response.okay(accessService.bind(command));
  }

  @DeleteMapping("/bindings")
  public Response<Void> unbind(
      @RequestBody final PhysicalModelResourceBindingCommand command
  ) {
    accessService.unbind(command);
    return Response.okay();
  }

  @GetMapping("/{modelId}/bindings")
  public Response<List<PhysicalModelResourceBindingView>> bindings(
      @PathVariable final String modelId
  ) {
    return Response.okay(accessService.list(modelId));
  }
}
