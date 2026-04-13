package org.simplepoint.plugin.dna.federation.api.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.dna.federation.api.entity.DataLineageEdge;
import org.simplepoint.plugin.dna.federation.api.entity.DataLineageNode;

/**
 * Service contract for data lineage management. Manages both
 * lineage nodes (physical objects) and edges (directed flows).
 */
public interface DataLineageService extends BaseService<DataLineageNode, String> {

  /**
   * Finds an active lineage node by id.
   *
   * @param id node id
   * @return active node
   */
  Optional<DataLineageNode> findActiveNodeById(String id);

  /**
   * Counts all active lineage nodes.
   *
   * @return active node count
   */
  long countActiveNodes();

  /**
   * Creates a lineage edge connecting two nodes.
   *
   * @param edge edge to create
   * @return created edge
   */
  DataLineageEdge createEdge(DataLineageEdge edge);

  /**
   * Lists all edges for a given node (both upstream and downstream).
   *
   * @param nodeId the node id
   * @return edges touching this node
   */
  List<DataLineageEdge> findEdgesByNodeId(String nodeId);

  /**
   * Removes a lineage edge by id.
   *
   * @param edgeId edge id
   */
  void removeEdge(String edgeId);

  /**
   * Returns the full lineage graph for a given node, including upstream
   * and downstream nodes with their edges. Used for visualization.
   *
   * @param nodeId starting node id
   * @param depth  max traversal depth (0 = direct neighbors only)
   * @return graph containing "nodes" and "edges" lists
   */
  Map<String, Object> getLineageGraph(String nodeId, int depth);

  /**
   * Automatically records lineage from a SQL query execution. Extracts source
   * table references from the Calcite EXPLAIN PLAN and creates lineage nodes
   * and edges for any DML target table.
   *
   * @param sql           the executed SQL text
   * @param planText      Calcite EXPLAIN PLAN output
   * @param dataSources   map of datasource code → datasource definition id
   * @param targetTable   DML target table reference (nullable for SELECT queries)
   * @param targetDsId    DML target datasource definition id (nullable)
   */
  void recordQueryLineage(
      String sql,
      String planText,
      Map<String, String> dataSources,
      List<String> targetTable,
      String targetDsId);
}
