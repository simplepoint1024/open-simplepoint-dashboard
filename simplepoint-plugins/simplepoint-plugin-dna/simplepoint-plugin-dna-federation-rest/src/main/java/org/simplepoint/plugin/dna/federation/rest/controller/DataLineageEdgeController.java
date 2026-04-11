package org.simplepoint.plugin.dna.federation.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.dna.federation.api.constants.DnaFederationPaths;
import org.simplepoint.plugin.dna.federation.api.entity.DataLineageEdge;
import org.simplepoint.plugin.dna.federation.api.service.DataLineageService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Data lineage edge management endpoints.
 */
@RestController
@RequestMapping({DnaFederationPaths.DATA_LINEAGE_EDGES, DnaFederationPaths.PLATFORM_DATA_LINEAGE_EDGES})
@Tag(name = "数据血缘边管理", description = "用于管理数据血缘图谱中的有向边")
public class DataLineageEdgeController {

  private final DataLineageService lineageService;

  /**
   * Creates a data lineage edge controller.
   *
   * @param lineageService data lineage service
   */
  public DataLineageEdgeController(final DataLineageService lineageService) {
    this.lineageService = lineageService;
  }

  /**
   * Lists edges touching a given node.
   *
   * @param nodeId the node id
   * @return edges for the node
   */
  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.data-lineage.view')")
  @Operation(summary = "查询节点关联的边", description = "查询指定节点的上下游血缘边")
  public Response<List<DataLineageEdge>> listByNode(@RequestParam("nodeId") final String nodeId) {
    return Response.okay(lineageService.findEdgesByNodeId(nodeId));
  }

  /**
   * Creates a lineage edge.
   *
   * @param edge edge definition
   * @return created edge
   */
  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.data-lineage.create')")
  @Operation(summary = "新增血缘边", description = "新增一条数据血缘有向边")
  public Response<?> add(@RequestBody final DataLineageEdge edge) {
    try {
      return Response.okay(lineageService.createEdge(edge));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Removes a lineage edge by id.
   *
   * @param id edge id
   * @return result
   */
  @DeleteMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.data-lineage.delete')")
  @Operation(summary = "删除血缘边", description = "删除一条数据血缘有向边")
  public Response<?> remove(@RequestParam("id") final String id) {
    try {
      lineageService.removeEdge(id);
      return Response.okay(id);
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  private Response<String> badRequest(final String message) {
    return Response.of(
        ResponseEntity.badRequest()
            .contentType(MediaType.TEXT_PLAIN)
            .body(message)
    );
  }
}
