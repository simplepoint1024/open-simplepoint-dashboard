package org.simplepoint.plugin.dna.federation.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.dna.federation.api.constants.DnaFederationPaths;
import org.simplepoint.plugin.dna.federation.api.entity.DataLineageEdge;
import org.simplepoint.plugin.dna.federation.api.entity.DataLineageNode;
import org.simplepoint.plugin.dna.federation.api.service.DataLineageService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Data lineage node management endpoints.
 */
@RestController
@RequestMapping({DnaFederationPaths.DATA_LINEAGE_NODES, DnaFederationPaths.PLATFORM_DATA_LINEAGE_NODES})
@Tag(name = "数据血缘节点管理", description = "用于管理数据血缘图谱中的节点")
public class DataLineageNodeController
    extends BaseController<DataLineageService, DataLineageNode, String> {

  /**
   * Creates a data lineage node controller.
   *
   * @param service data lineage service
   */
  public DataLineageNodeController(final DataLineageService service) {
    super(service);
  }

  /**
   * Pages lineage nodes.
   *
   * @param attributes filter attributes
   * @param pageable   paging arguments
   * @return paged lineage nodes
   */
  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.data-lineage.view')")
  @Operation(summary = "分页查询血缘节点", description = "根据条件分页查询数据血缘节点")
  public Response<Page<DataLineageNode>> limit(
      @RequestParam final Map<String, String> attributes,
      final Pageable pageable
  ) {
    return limit(service.limit(attributes, pageable), DataLineageNode.class);
  }

  /**
   * Creates a lineage node.
   *
   * @param data node definition
   * @return created node
   */
  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.data-lineage.create')")
  @Operation(summary = "新增血缘节点", description = "新增一个数据血缘节点")
  public Response<?> add(@RequestBody final DataLineageNode data) {
    try {
      return ok(service.create(data));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Updates a lineage node.
   *
   * @param data node definition
   * @return updated node
   */
  @PutMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.data-lineage.edit')")
  @Operation(summary = "修改血缘节点", description = "修改一个已存在的数据血缘节点")
  public Response<?> modify(@RequestBody final DataLineageNode data) {
    try {
      return ok(service.modifyById(data));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Deletes lineage nodes by ids.
   *
   * @param ids comma-separated ids
   * @return deleted ids
   */
  @DeleteMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.data-lineage.delete')")
  @Operation(summary = "删除血缘节点", description = "根据 ID 集合删除数据血缘节点")
  public Response<?> remove(@RequestParam("ids") final String ids) {
    try {
      Set<String> idSet = StringUtil.stringToSet(ids);
      service.removeByIds(idSet);
      return ok(idSet);
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Returns the lineage graph for a given node, including connected
   * upstream and downstream nodes with edges up to the specified depth.
   *
   * @param nodeId starting node id
   * @param depth  traversal depth (default 2)
   * @return lineage graph with nodes and edges
   */
  @GetMapping("/graph")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.data-lineage.view')")
  @Operation(summary = "获取血缘图谱", description = "获取指定节点的血缘关系图谱")
  public Response<?> graph(
      @RequestParam("nodeId") final String nodeId,
      @RequestParam(value = "depth", defaultValue = "2") final int depth
  ) {
    try {
      return ok(service.getLineageGraph(nodeId, depth));
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
