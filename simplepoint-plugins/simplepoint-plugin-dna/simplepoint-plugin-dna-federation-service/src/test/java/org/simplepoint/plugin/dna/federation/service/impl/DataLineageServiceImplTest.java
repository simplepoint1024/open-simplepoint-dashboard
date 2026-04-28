package org.simplepoint.plugin.dna.federation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.federation.api.entity.DataLineageEdge;
import org.simplepoint.plugin.dna.federation.api.entity.DataLineageNode;
import org.simplepoint.plugin.dna.federation.api.repository.DataLineageEdgeRepository;
import org.simplepoint.plugin.dna.federation.api.repository.DataLineageNodeRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class DataLineageServiceImplTest {

  @Mock
  private DataLineageNodeRepository nodeRepository;

  @Mock
  private DataLineageEdgeRepository edgeRepository;

  @Mock
  private DetailsProviderService detailsProviderService;

  @Mock
  private JdbcDataSourceDefinitionService dataSourceService;

  private DataLineageServiceImpl service() {
    return new DataLineageServiceImpl(nodeRepository, edgeRepository, detailsProviderService, dataSourceService);
  }

  // ---- findActiveNodeById ----

  @Test
  void findActiveNodeByIdReturnsDecoratedNodeWhenFound() {
    DataLineageNode node = node("n1", "catalog-1", "orders");
    when(nodeRepository.findActiveById("n1")).thenReturn(Optional.of(node));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL")));

    Optional<DataLineageNode> result = service().findActiveNodeById("n1");

    assertThat(result).isPresent();
    assertThat(result.get().getCatalogName()).isEqualTo("MySQL");
  }

  @Test
  void findActiveNodeByIdReturnsEmptyWhenNotFound() {
    when(nodeRepository.findActiveById("missing")).thenReturn(Optional.empty());

    Optional<DataLineageNode> result = service().findActiveNodeById("missing");

    assertThat(result).isEmpty();
  }

  @Test
  void findActiveNodeByIdSetsNullCatalogNameWhenDsAbsent() {
    DataLineageNode node = node("n1", "gone-catalog", "orders");
    when(nodeRepository.findActiveById("n1")).thenReturn(Optional.of(node));
    when(dataSourceService.findActiveById("gone-catalog")).thenReturn(Optional.empty());

    Optional<DataLineageNode> result = service().findActiveNodeById("n1");

    assertThat(result).isPresent();
    assertThat(result.get().getCatalogName()).isNull();
  }

  // ---- limit ----

  @Test
  void limitDecoratesPageContent() {
    DataLineageNode node = node("n1", "catalog-1", "orders");
    Page<DataLineageNode> page = new PageImpl<>(List.of(node), PageRequest.of(0, 10), 1);
    when(nodeRepository.limit(any(), any())).thenReturn((Page) page);
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "PG")));

    Page<DataLineageNode> result = service().limit(Map.of(), PageRequest.of(0, 10));

    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().get(0).getCatalogName()).isEqualTo("PG");
  }

  @Test
  void limitWithNullAttributesDoesNotThrow() {
    Page<DataLineageNode> empty = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
    when(nodeRepository.limit(any(), any())).thenReturn((Page) empty);

    Page<DataLineageNode> result = service().limit(null, PageRequest.of(0, 10));

    assertThat(result.getContent()).isEmpty();
  }

  // ---- create ----

  @Test
  void createSavesValidNode() {
    DataLineageNode node = validNode();
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL")));
    when(nodeRepository.save(node)).thenReturn(node);

    DataLineageNode result = service().create(node);

    assertThat(result).isNotNull();
    verify(nodeRepository).save(node);
  }

  @Test
  void createRejectsNullName() {
    DataLineageNode node = validNode();
    node.setName(null);

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service().create(node));
    assertThat(ex.getMessage()).contains("节点名称");
  }

  @Test
  void createRejectsNullCatalogId() {
    DataLineageNode node = validNode();
    node.setCatalogId(null);

    assertThrows(IllegalArgumentException.class, () -> service().create(node));
  }

  @Test
  void createRejectsNullNodeType() {
    DataLineageNode node = validNode();
    node.setNodeType(null);

    assertThrows(IllegalArgumentException.class, () -> service().create(node));
  }

  @Test
  void createRejectsNullTableName() {
    DataLineageNode node = validNode();
    node.setTableName(null);

    assertThrows(IllegalArgumentException.class, () -> service().create(node));
  }

  @Test
  void createRejectsInvalidNodeType() {
    DataLineageNode node = validNode();
    node.setNodeType("INVALID_TYPE");

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service().create(node));
    assertThat(ex.getMessage()).contains("节点类型必须为");
  }

  @Test
  void createRejectsDisabledDataSource() {
    DataLineageNode node = validNode();
    JdbcDataSourceDefinition ds = enabledDs("catalog-1", "MySQL");
    ds.setEnabled(false);
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(ds));

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service().create(node));
    assertThat(ex.getMessage()).contains("数据源不存在或未启用");
  }

  @Test
  void createRejectsAbsentDataSource() {
    DataLineageNode node = validNode();
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.empty());

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service().create(node));
    assertThat(ex.getMessage()).contains("数据源不存在或未启用");
  }

  // ---- modifyById ----

  @Test
  void modifyByIdThrowsWhenNodeNotFound() {
    DataLineageNode entity = validNode();
    entity.setId("not-found");
    when(nodeRepository.findActiveById("not-found")).thenReturn(Optional.empty());

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service().modifyById(entity));
    assertThat(ex.getMessage()).contains("血缘节点不存在");
  }

  @Test
  void modifyByIdUpdatesNode() {
    DataLineageNode existing = validNode();
    existing.setId("n1");
    DataLineageNode entity = validNode();
    entity.setId("n1");

    when(nodeRepository.findActiveById("n1")).thenReturn(Optional.of(existing));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL")));
    when(nodeRepository.findById("n1")).thenReturn(Optional.empty());
    when(nodeRepository.updateById(entity)).thenReturn(entity);

    DataLineageNode result = service().modifyById(entity);

    assertThat(result).isNotNull();
  }

  // ---- createEdge ----

  @Test
  void createEdgeValidEdge() {
    DataLineageEdge edge = validEdge();
    DataLineageNode srcNode = node("src-1", "catalog-1", "source_table");
    DataLineageNode tgtNode = node("tgt-1", "catalog-1", "target_table");
    when(nodeRepository.findActiveById("src-1")).thenReturn(Optional.of(srcNode));
    when(nodeRepository.findActiveById("tgt-1")).thenReturn(Optional.of(tgtNode));
    when(edgeRepository.save(edge)).thenReturn(edge);

    DataLineageEdge result = service().createEdge(edge);

    assertThat(result).isNotNull();
    verify(edgeRepository).save(edge);
  }

  @Test
  void createEdgeRejectsNullSourceNode() {
    DataLineageEdge edge = validEdge();
    edge.setSourceNodeId(null);

    assertThrows(IllegalArgumentException.class, () -> service().createEdge(edge));
  }

  @Test
  void createEdgeRejectsNullTargetNode() {
    DataLineageEdge edge = validEdge();
    edge.setTargetNodeId(null);

    assertThrows(IllegalArgumentException.class, () -> service().createEdge(edge));
  }

  @Test
  void createEdgeRejectsSelfLoop() {
    DataLineageEdge edge = validEdge();
    edge.setTargetNodeId("src-1");

    assertThrows(IllegalArgumentException.class, () -> service().createEdge(edge));
  }

  @Test
  void createEdgeRejectsInvalidEdgeType() {
    DataLineageEdge edge = validEdge();
    edge.setEdgeType("INVALID");

    assertThrows(IllegalArgumentException.class, () -> service().createEdge(edge));
  }

  @Test
  void createEdgeRejectsAbsentSourceNode() {
    DataLineageEdge edge = validEdge();
    when(nodeRepository.findActiveById("src-1")).thenReturn(Optional.empty());

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service().createEdge(edge));
    assertThat(ex.getMessage()).contains("源节点不存在");
  }

  @Test
  void createEdgeRejectsAbsentTargetNode() {
    DataLineageEdge edge = validEdge();
    when(nodeRepository.findActiveById("src-1")).thenReturn(Optional.of(node("src-1", "c", "t")));
    when(nodeRepository.findActiveById("tgt-1")).thenReturn(Optional.empty());

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service().createEdge(edge));
    assertThat(ex.getMessage()).contains("目标节点不存在");
  }

  @Test
  void createEdgeRejectsNullEdge() {
    assertThrows(IllegalArgumentException.class, () -> service().createEdge(null));
  }

  @Test
  void createEdgeRejectsNullEdgeType() {
    DataLineageEdge edge = validEdge();
    edge.setEdgeType(null);

    assertThrows(IllegalArgumentException.class, () -> service().createEdge(edge));
  }

  // ---- findEdgesByNodeId ----

  @Test
  void findEdgesByNodeIdReturnsCombinedEdges() {
    DataLineageEdge outgoing = edge("e1", "n1", "n2", "DIRECT");
    DataLineageEdge incoming = edge("e2", "n0", "n1", "ETL");
    when(edgeRepository.findActiveBySourceNodeId("n1")).thenReturn(List.of(outgoing));
    when(edgeRepository.findActiveByTargetNodeId("n1")).thenReturn(List.of(incoming));
    when(nodeRepository.findActiveById(any())).thenReturn(Optional.empty());

    List<DataLineageEdge> result = service().findEdgesByNodeId("n1");

    assertThat(result).hasSize(2);
  }

  @Test
  void findEdgesByNodeIdReturnsEmptyWhenNoEdges() {
    when(edgeRepository.findActiveBySourceNodeId("n1")).thenReturn(List.of());
    when(edgeRepository.findActiveByTargetNodeId("n1")).thenReturn(List.of());

    List<DataLineageEdge> result = service().findEdgesByNodeId("n1");

    assertThat(result).isEmpty();
  }

  // ---- removeEdge ----

  @Test
  void removeEdgeDelegatesToRepository() {
    service().removeEdge("e1");
    verify(edgeRepository).deleteById("e1");
  }

  // ---- getLineageGraph ----

  @Test
  void getLineageGraphReturnsSingleNodeGraphWhenNoEdges() {
    DataLineageNode root = node("root", "catalog-1", "table");
    when(edgeRepository.findActiveBySourceNodeId("root")).thenReturn(List.of());
    when(edgeRepository.findActiveByTargetNodeId("root")).thenReturn(List.of());
    when(nodeRepository.findActiveById("root")).thenReturn(Optional.of(root));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL")));

    Map<String, Object> graph = service().getLineageGraph("root", 2);

    assertThat(graph).containsKey("nodes");
    assertThat(graph).containsKey("edges");
    assertThat(graph.get("rootNodeId")).isEqualTo("root");
    assertThat((List<?>) graph.get("nodes")).hasSize(1);
    assertThat((List<?>) graph.get("edges")).isEmpty();
  }

  @Test
  void getLineageGraphClampsDepthToMax() {
    when(edgeRepository.findActiveBySourceNodeId(any())).thenReturn(List.of());
    when(edgeRepository.findActiveByTargetNodeId(any())).thenReturn(List.of());
    when(nodeRepository.findActiveById("root")).thenReturn(Optional.of(node("root", "c", "t")));
    when(dataSourceService.findActiveById("c")).thenReturn(Optional.of(enabledDs("c", "DB")));

    // depth=100 should be clamped to 10
    Map<String, Object> graph = service().getLineageGraph("root", 100);

    assertThat(graph).isNotNull();
  }

  @Test
  void getLineageGraphExpandsOneHop() {
    DataLineageNode root = node("root", "c", "t_root");
    DataLineageNode child = node("child", "c", "t_child");
    DataLineageEdge e = edge("e1", "root", "child", "DIRECT");

    when(edgeRepository.findActiveBySourceNodeId("root")).thenReturn(List.of(e));
    when(edgeRepository.findActiveByTargetNodeId("root")).thenReturn(List.of());
    when(edgeRepository.findActiveBySourceNodeId("child")).thenReturn(List.of());
    when(edgeRepository.findActiveByTargetNodeId("child")).thenReturn(List.of());
    when(nodeRepository.findActiveById("root")).thenReturn(Optional.of(root));
    when(nodeRepository.findActiveById("child")).thenReturn(Optional.of(child));
    when(dataSourceService.findActiveById("c")).thenReturn(Optional.of(enabledDs("c", "DB")));

    Map<String, Object> graph = service().getLineageGraph("root", 1);

    assertThat((List<?>) graph.get("nodes")).hasSize(2);
    assertThat((List<?>) graph.get("edges")).hasSize(1);
  }

  // ---- recordQueryLineage ----

  @Test
  void recordQueryLineageSkipsWhenPlanBlank() {
    service().recordQueryLineage("SELECT 1", "", Map.of("ds", "ds-1"), List.of("tgt"), "ds-1");
    verify(edgeRepository, never()).save(any());
  }

  @Test
  void recordQueryLineageSkipsWhenDataSourcesNull() {
    service().recordQueryLineage("SELECT 1", "some plan", null, List.of("tgt"), "ds-1");
    verify(edgeRepository, never()).save(any());
  }

  @Test
  void recordQueryLineageSkipsWhenTargetTableEmpty() {
    service().recordQueryLineage("SELECT 1", "some plan", Map.of("ds", "ds-1"), List.of(), "ds-1");
    verify(edgeRepository, never()).save(any());
  }

  @Test
  void recordQueryLineageSkipsWhenTargetDsIdNull() {
    service().recordQueryLineage("SELECT 1", "some plan", Map.of("ds", "ds-1"), List.of("tgt"), null);
    verify(edgeRepository, never()).save(any());
  }

  // ---- helpers ----

  private DataLineageNode validNode() {
    DataLineageNode node = new DataLineageNode();
    node.setName("Orders");
    node.setCatalogId("catalog-1");
    node.setNodeType("TABLE");
    node.setTableName("orders");
    return node;
  }

  private static DataLineageNode node(final String id, final String catalogId, final String tableName) {
    DataLineageNode node = new DataLineageNode();
    node.setId(id);
    node.setCatalogId(catalogId);
    node.setTableName(tableName);
    node.setName(tableName);
    node.setNodeType("TABLE");
    return node;
  }

  private DataLineageEdge validEdge() {
    DataLineageEdge edge = new DataLineageEdge();
    edge.setSourceNodeId("src-1");
    edge.setTargetNodeId("tgt-1");
    edge.setEdgeType("DIRECT");
    return edge;
  }

  private static DataLineageEdge edge(final String id, final String src, final String tgt, final String type) {
    DataLineageEdge e = new DataLineageEdge();
    e.setId(id);
    e.setSourceNodeId(src);
    e.setTargetNodeId(tgt);
    e.setEdgeType(type);
    return e;
  }

  private static JdbcDataSourceDefinition enabledDs(final String id, final String name) {
    JdbcDataSourceDefinition ds = new JdbcDataSourceDefinition();
    ds.setId(id);
    ds.setName(name);
    ds.setCode(name);
    ds.setEnabled(true);
    return ds;
  }
}
