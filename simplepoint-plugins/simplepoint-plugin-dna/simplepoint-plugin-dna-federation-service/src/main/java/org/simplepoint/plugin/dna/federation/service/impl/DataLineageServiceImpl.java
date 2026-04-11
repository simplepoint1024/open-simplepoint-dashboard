package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.normalizeLikeQuery;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.requireEntityId;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.requireValue;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.trimToNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.federation.api.entity.DataLineageEdge;
import org.simplepoint.plugin.dna.federation.api.entity.DataLineageNode;
import org.simplepoint.plugin.dna.federation.api.repository.DataLineageEdgeRepository;
import org.simplepoint.plugin.dna.federation.api.repository.DataLineageNodeRepository;
import org.simplepoint.plugin.dna.federation.api.service.DataLineageService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Data lineage service implementation. Manages lineage nodes and edges,
 * and provides graph traversal for lineage visualization.
 */
@Service
public class DataLineageServiceImpl
    extends BaseServiceImpl<DataLineageNodeRepository, DataLineageNode, String>
    implements DataLineageService {

  private static final Set<String> VALID_NODE_TYPES = Set.of(
      "TABLE", "VIEW", "COLUMN", "ETL", "STREAM", "API", "FILE"
  );

  private static final Set<String> VALID_EDGE_TYPES = Set.of(
      "DIRECT", "ETL", "DERIVED", "COPY", "AGGREGATION", "FILTER", "JOIN"
  );

  private static final int MAX_TRAVERSAL_DEPTH = 10;

  private final DataLineageNodeRepository nodeRepository;

  private final DataLineageEdgeRepository edgeRepository;

  private final JdbcDataSourceDefinitionService dataSourceService;

  /**
   * Creates a data lineage service.
   *
   * @param nodeRepository         node repository
   * @param edgeRepository         edge repository
   * @param detailsProviderService details provider service
   * @param dataSourceService      datasource service
   */
  public DataLineageServiceImpl(
      final DataLineageNodeRepository nodeRepository,
      final DataLineageEdgeRepository edgeRepository,
      final DetailsProviderService detailsProviderService,
      final JdbcDataSourceDefinitionService dataSourceService
  ) {
    super(nodeRepository, detailsProviderService);
    this.nodeRepository = nodeRepository;
    this.edgeRepository = edgeRepository;
    this.dataSourceService = dataSourceService;
  }

  /** {@inheritDoc} */
  @Override
  public Optional<DataLineageNode> findActiveNodeById(final String id) {
    return nodeRepository.findActiveById(id).map(this::decorateNode);
  }

  /** {@inheritDoc} */
  @Override
  public long countActiveNodes() {
    Map<String, String> attrs = new LinkedHashMap<>();
    attrs.put("deletedAt", "is:null");
    return super.limit(attrs, Pageable.ofSize(1)).getTotalElements();
  }

  /** {@inheritDoc} */
  @Override
  public <S extends DataLineageNode> Page<S> limit(final Map<String, String> attributes, final Pageable pageable) {
    Map<String, String> normalized = new LinkedHashMap<>();
    if (attributes != null) {
      normalized.putAll(attributes);
    }
    normalized.put("deletedAt", "is:null");
    normalizeLikeQuery(normalized, "name");
    normalizeLikeQuery(normalized, "tableName");
    normalizeLikeQuery(normalized, "schemaName");
    normalizeLikeQuery(normalized, "tags");
    Page<S> page = super.limit(normalized, pageable);
    decorateNodes(page.getContent());
    return page;
  }

  /** {@inheritDoc} */
  @Override
  public <S extends DataLineageNode> S create(final S entity) {
    normalizeAndValidateNode(entity, null);
    S saved = super.create(entity);
    decorateNode(saved);
    return saved;
  }

  /** {@inheritDoc} */
  @Override
  public <S extends DataLineageNode> DataLineageNode modifyById(final S entity) {
    nodeRepository.findActiveById(requireEntityId(entity))
        .orElseThrow(() -> new IllegalArgumentException("血缘节点不存在: " + entity.getId()));
    normalizeAndValidateNode(entity, entity.getId());
    DataLineageNode updated = (DataLineageNode) super.modifyById(entity);
    decorateNode(updated);
    return updated;
  }

  /** {@inheritDoc} */
  @Override
  public DataLineageEdge createEdge(final DataLineageEdge edge) {
    normalizeAndValidateEdge(edge);
    DataLineageEdge saved = edgeRepository.save(edge);
    decorateEdge(saved);
    return saved;
  }

  /** {@inheritDoc} */
  @Override
  public List<DataLineageEdge> findEdgesByNodeId(final String nodeId) {
    List<DataLineageEdge> edges = new ArrayList<>();
    edges.addAll(edgeRepository.findActiveBySourceNodeId(nodeId));
    edges.addAll(edgeRepository.findActiveByTargetNodeId(nodeId));
    decorateEdges(edges);
    return edges;
  }

  /** {@inheritDoc} */
  @Override
  public void removeEdge(final String edgeId) {
    edgeRepository.deleteById(edgeId);
  }

  /** {@inheritDoc} */
  @Override
  public Map<String, Object> getLineageGraph(final String nodeId, final int depth) {
    int effectiveDepth = Math.min(Math.max(depth, 0), MAX_TRAVERSAL_DEPTH);

    Set<String> visitedNodeIds = new LinkedHashSet<>();
    Set<DataLineageEdge> collectedEdges = new LinkedHashSet<>();

    // BFS traversal
    Set<String> currentLevel = new HashSet<>();
    currentLevel.add(nodeId);

    for (int i = 0; i <= effectiveDepth && !currentLevel.isEmpty(); i++) {
      visitedNodeIds.addAll(currentLevel);
      Set<String> nextLevel = new HashSet<>();

      for (String nid : currentLevel) {
        List<DataLineageEdge> downstream = edgeRepository.findActiveBySourceNodeId(nid);
        List<DataLineageEdge> upstream = edgeRepository.findActiveByTargetNodeId(nid);

        collectedEdges.addAll(downstream);
        collectedEdges.addAll(upstream);

        for (DataLineageEdge e : downstream) {
          if (!visitedNodeIds.contains(e.getTargetNodeId())) {
            nextLevel.add(e.getTargetNodeId());
          }
        }
        for (DataLineageEdge e : upstream) {
          if (!visitedNodeIds.contains(e.getSourceNodeId())) {
            nextLevel.add(e.getSourceNodeId());
          }
        }
      }

      currentLevel = nextLevel;
    }

    // Collect all nodes
    List<DataLineageNode> nodes = visitedNodeIds.stream()
        .map(nodeRepository::findActiveById)
        .flatMap(Optional::stream)
        .toList();
    decorateNodes(nodes);

    List<DataLineageEdge> edgeList = new ArrayList<>(collectedEdges);
    decorateEdges(edgeList);

    Map<String, Object> graph = new LinkedHashMap<>();
    graph.put("nodes", nodes);
    graph.put("edges", edgeList);
    graph.put("rootNodeId", nodeId);
    return graph;
  }

  private void normalizeAndValidateNode(final DataLineageNode entity, final String currentId) {
    if (entity == null) {
      throw new IllegalArgumentException("血缘节点不能为空");
    }
    entity.setName(requireValue(entity.getName(), "节点名称不能为空"));
    entity.setCatalogId(requireValue(entity.getCatalogId(), "数据源不能为空"));
    entity.setNodeType(requireValue(entity.getNodeType(), "节点类型不能为空"));
    entity.setTableName(requireValue(entity.getTableName(), "表名不能为空"));
    entity.setSchemaName(trimToNull(entity.getSchemaName()));
    entity.setColumnName(trimToNull(entity.getColumnName()));
    entity.setTags(trimToNull(entity.getTags()));
    entity.setDescription(trimToNull(entity.getDescription()));

    if (!VALID_NODE_TYPES.contains(entity.getNodeType())) {
      throw new IllegalArgumentException(
          "节点类型必须为 " + String.join(", ", VALID_NODE_TYPES));
    }
    dataSourceService.findActiveById(entity.getCatalogId())
        .filter(ds -> Boolean.TRUE.equals(ds.getEnabled()))
        .orElseThrow(() -> new IllegalArgumentException("数据源不存在或未启用: " + entity.getCatalogId()));
  }

  private void normalizeAndValidateEdge(final DataLineageEdge edge) {
    if (edge == null) {
      throw new IllegalArgumentException("血缘边不能为空");
    }
    edge.setSourceNodeId(requireValue(edge.getSourceNodeId(), "源节点不能为空"));
    edge.setTargetNodeId(requireValue(edge.getTargetNodeId(), "目标节点不能为空"));
    edge.setEdgeType(requireValue(edge.getEdgeType(), "边类型不能为空"));
    edge.setTransformDescription(trimToNull(edge.getTransformDescription()));
    edge.setDescription(trimToNull(edge.getDescription()));

    if (!VALID_EDGE_TYPES.contains(edge.getEdgeType())) {
      throw new IllegalArgumentException(
          "边类型必须为 " + String.join(", ", VALID_EDGE_TYPES));
    }
    if (edge.getSourceNodeId().equals(edge.getTargetNodeId())) {
      throw new IllegalArgumentException("源节点和目标节点不能相同");
    }
    nodeRepository.findActiveById(edge.getSourceNodeId())
        .orElseThrow(() -> new IllegalArgumentException("源节点不存在: " + edge.getSourceNodeId()));
    nodeRepository.findActiveById(edge.getTargetNodeId())
        .orElseThrow(() -> new IllegalArgumentException("目标节点不存在: " + edge.getTargetNodeId()));
  }

  private DataLineageNode decorateNode(final DataLineageNode item) {
    if (item == null) {
      return null;
    }
    dataSourceService.findActiveById(item.getCatalogId()).ifPresentOrElse(
        ds -> item.setCatalogName(ds.getName()),
        () -> item.setCatalogName(null)
    );
    return item;
  }

  private <S extends DataLineageNode> void decorateNodes(final Collection<S> items) {
    if (items == null || items.isEmpty()) {
      return;
    }
    Set<String> catalogIds = items.stream()
        .map(DataLineageNode::getCatalogId)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
    Map<String, JdbcDataSourceDefinition> dsMap = catalogIds.stream()
        .map(dataSourceService::findActiveById)
        .flatMap(Optional::stream)
        .collect(Collectors.toMap(JdbcDataSourceDefinition::getId, ds -> ds, (l, r) -> l));
    items.forEach(item -> {
      JdbcDataSourceDefinition ds = dsMap.get(item.getCatalogId());
      item.setCatalogName(ds != null ? ds.getName() : null);
    });
  }

  private void decorateEdge(final DataLineageEdge edge) {
    if (edge == null) {
      return;
    }
    nodeRepository.findActiveById(edge.getSourceNodeId())
        .ifPresent(n -> edge.setSourceNodeName(n.getName()));
    nodeRepository.findActiveById(edge.getTargetNodeId())
        .ifPresent(n -> edge.setTargetNodeName(n.getName()));
  }

  private void decorateEdges(final Collection<DataLineageEdge> edges) {
    if (edges == null || edges.isEmpty()) {
      return;
    }
    Set<String> nodeIds = new HashSet<>();
    edges.forEach(e -> {
      nodeIds.add(e.getSourceNodeId());
      nodeIds.add(e.getTargetNodeId());
    });
    Map<String, DataLineageNode> nodesById = nodeIds.stream()
        .map(nodeRepository::findActiveById)
        .flatMap(Optional::stream)
        .collect(Collectors.toMap(DataLineageNode::getId, n -> n, (l, r) -> l));
    edges.forEach(e -> {
      DataLineageNode src = nodesById.get(e.getSourceNodeId());
      DataLineageNode tgt = nodesById.get(e.getTargetNodeId());
      e.setSourceNodeName(src != null ? src.getName() : null);
      e.setTargetNodeName(tgt != null ? tgt.getName() : null);
    });
  }
}
