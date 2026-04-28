package org.simplepoint.plugin.dna.federation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryPolicy;
import org.simplepoint.plugin.dna.federation.api.repository.FederationQueryPolicyRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class FederationQueryPolicyServiceImplTest {

  @Mock
  private FederationQueryPolicyRepository repository;

  @Mock
  private DetailsProviderService detailsProviderService;

  @Mock
  private JdbcDataSourceDefinitionService dataSourceService;

  private FederationQueryPolicyServiceImpl service() {
    return new FederationQueryPolicyServiceImpl(repository, detailsProviderService, dataSourceService);
  }

  // ---- findActiveById ----

  @Test
  void findActiveByIdReturnsDecoratedPolicy() {
    FederationQueryPolicy policy = validPolicy();
    policy.setId("p1");
    when(repository.findActiveById("p1")).thenReturn(Optional.of(policy));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL", "mysql")));

    Optional<FederationQueryPolicy> result = service().findActiveById("p1");

    assertThat(result).isPresent();
    assertThat(result.get().getCatalogName()).isEqualTo("MySQL");
    assertThat(result.get().getCatalogCode()).isEqualTo("mysql");
  }

  @Test
  void findActiveByIdReturnsEmptyWhenNotFound() {
    when(repository.findActiveById("missing")).thenReturn(Optional.empty());

    assertThat(service().findActiveById("missing")).isEmpty();
  }

  @Test
  void findActiveByIdSetsNullCatalogFieldsWhenDsAbsent() {
    FederationQueryPolicy policy = validPolicy();
    policy.setId("p1");
    when(repository.findActiveById("p1")).thenReturn(Optional.of(policy));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.empty());

    Optional<FederationQueryPolicy> result = service().findActiveById("p1");

    assertThat(result).isPresent();
    assertThat(result.get().getCatalogName()).isNull();
    assertThat(result.get().getCatalogCode()).isNull();
  }

  // ---- findActiveByCode ----

  @Test
  void findActiveByCodeTrimsAndDelegates() {
    FederationQueryPolicy policy = validPolicy();
    when(repository.findActiveByCode("POLICY_A")).thenReturn(Optional.of(policy));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL", "mysql")));

    Optional<FederationQueryPolicy> result = service().findActiveByCode("  POLICY_A  ");

    assertThat(result).isPresent();
  }

  // ---- limit ----

  @Test
  void limitDecoratesPageContent() {
    FederationQueryPolicy policy = validPolicy();
    Page<FederationQueryPolicy> page = new PageImpl<>(List.of(policy), PageRequest.of(0, 10), 1);
    when(repository.limit(any(), any())).thenReturn((Page) page);
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL", "mysql")));

    Page<FederationQueryPolicy> result = service().limit(Map.of(), PageRequest.of(0, 10));

    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().get(0).getCatalogName()).isEqualTo("MySQL");
  }

  @Test
  void limitHandlesNullAttributes() {
    Page<FederationQueryPolicy> empty = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
    when(repository.limit(any(), any())).thenReturn((Page) empty);

    Page<FederationQueryPolicy> result = service().limit(null, PageRequest.of(0, 10));

    assertThat(result.getContent()).isEmpty();
  }

  // ---- create ----

  @Test
  void createSavesValidPolicy() {
    FederationQueryPolicy policy = validPolicy();
    when(repository.findActiveByCode("POLICY_A")).thenReturn(Optional.empty());
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL", "mysql")));
    when(repository.save(policy)).thenReturn(policy);

    FederationQueryPolicy result = service().create(policy);

    assertThat(result).isNotNull();
    verify(repository).save(policy);
  }

  @Test
  void createAppliesDefaults() {
    FederationQueryPolicy policy = validPolicy();
    policy.setAllowSqlConsole(null);
    policy.setAllowCrossSourceJoin(null);
    policy.setMaxRows(null);
    policy.setTimeoutMs(null);
    policy.setEnabled(null);
    when(repository.findActiveByCode("POLICY_A")).thenReturn(Optional.empty());
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL", "mysql")));
    when(repository.save(policy)).thenReturn(policy);

    service().create(policy);

    assertThat(policy.getAllowSqlConsole()).isFalse();
    assertThat(policy.getAllowCrossSourceJoin()).isFalse();
    assertThat(policy.getMaxRows()).isEqualTo(5000);
    assertThat(policy.getTimeoutMs()).isEqualTo(30000);
    assertThat(policy.getEnabled()).isTrue();
  }

  @Test
  void createRejectsNullCatalogId() {
    FederationQueryPolicy policy = validPolicy();
    policy.setCatalogId(null);

    assertThrows(IllegalArgumentException.class, () -> service().create(policy));
  }

  @Test
  void createRejectsNullName() {
    FederationQueryPolicy policy = validPolicy();
    policy.setName(null);

    assertThrows(IllegalArgumentException.class, () -> service().create(policy));
  }

  @Test
  void createRejectsNullCode() {
    FederationQueryPolicy policy = validPolicy();
    policy.setCode(null);

    assertThrows(IllegalArgumentException.class, () -> service().create(policy));
  }

  @Test
  void createRejectsDuplicateCode() {
    FederationQueryPolicy policy = validPolicy();
    FederationQueryPolicy existing = validPolicy();
    existing.setId("other-id");
    when(repository.findActiveByCode("POLICY_A")).thenReturn(Optional.of(existing));

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service().create(policy));
    assertThat(ex.getMessage()).contains("查询策略编码已存在");
  }

  @Test
  void createRejectsMaxRowsLessThanOne() {
    FederationQueryPolicy policy = validPolicy();
    policy.setMaxRows(0);
    when(repository.findActiveByCode("POLICY_A")).thenReturn(Optional.empty());

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service().create(policy));
    assertThat(ex.getMessage()).contains("最大返回行数必须大于 0");
  }

  @Test
  void createRejectsTimeoutLessThanOne() {
    FederationQueryPolicy policy = validPolicy();
    policy.setTimeoutMs(0);
    when(repository.findActiveByCode("POLICY_A")).thenReturn(Optional.empty());

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service().create(policy));
    assertThat(ex.getMessage()).contains("超时毫秒数必须大于 0");
  }

  @Test
  void createRejectsDisabledDataSource() {
    FederationQueryPolicy policy = validPolicy();
    JdbcDataSourceDefinition ds = enabledDs("catalog-1", "MySQL", "mysql");
    ds.setEnabled(false);
    when(repository.findActiveByCode("POLICY_A")).thenReturn(Optional.empty());
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(ds));

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service().create(policy));
    assertThat(ex.getMessage()).contains("数据源不存在或未启用");
  }

  // ---- modifyById ----

  @Test
  void modifyByIdThrowsWhenPolicyNotFound() {
    FederationQueryPolicy entity = validPolicy();
    entity.setId("p-missing");
    when(repository.findActiveById("p-missing")).thenReturn(Optional.empty());

    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service().modifyById(entity));
    assertThat(ex.getMessage()).contains("查询策略不存在");
  }

  @Test
  void modifyByIdUpdatesPolicy() {
    FederationQueryPolicy current = validPolicy();
    current.setId("p1");
    FederationQueryPolicy entity = validPolicy();
    entity.setId("p1");

    when(repository.findActiveById("p1")).thenReturn(Optional.of(current));
    when(repository.findActiveByCode("POLICY_A")).thenReturn(Optional.of(current));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL", "mysql")));
    when(repository.findById("p1")).thenReturn(Optional.empty());
    when(repository.updateById(entity)).thenReturn(entity);

    FederationQueryPolicy result = service().modifyById(entity);

    assertThat(result).isNotNull();
  }

  @Test
  void modifyByIdInheritsNullFieldsFromCurrent() {
    FederationQueryPolicy current = validPolicy();
    current.setId("p1");
    current.setAllowSqlConsole(true);
    current.setMaxRows(200);
    current.setTimeoutMs(60000);
    current.setEnabled(false);

    FederationQueryPolicy entity = validPolicy();
    entity.setId("p1");
    entity.setAllowSqlConsole(null);
    entity.setMaxRows(null);
    entity.setTimeoutMs(null);
    entity.setEnabled(null);

    when(repository.findActiveById("p1")).thenReturn(Optional.of(current));
    when(repository.findActiveByCode("POLICY_A")).thenReturn(Optional.of(current));
    when(dataSourceService.findActiveById("catalog-1")).thenReturn(Optional.of(enabledDs("catalog-1", "MySQL", "mysql")));
    when(repository.findById("p1")).thenReturn(Optional.empty());
    when(repository.updateById(entity)).thenReturn(entity);

    service().modifyById(entity);

    assertThat(entity.getAllowSqlConsole()).isTrue();
    assertThat(entity.getMaxRows()).isEqualTo(200);
    assertThat(entity.getTimeoutMs()).isEqualTo(60000);
    assertThat(entity.getEnabled()).isFalse();
  }

  // ---- helpers ----

  private FederationQueryPolicy validPolicy() {
    FederationQueryPolicy policy = new FederationQueryPolicy();
    policy.setName("Default Policy");
    policy.setCode("POLICY_A");
    policy.setCatalogId("catalog-1");
    return policy;
  }

  private static JdbcDataSourceDefinition enabledDs(final String id, final String name, final String code) {
    JdbcDataSourceDefinition ds = new JdbcDataSourceDefinition();
    ds.setId(id);
    ds.setName(name);
    ds.setCode(code);
    ds.setEnabled(true);
    return ds;
  }
}
