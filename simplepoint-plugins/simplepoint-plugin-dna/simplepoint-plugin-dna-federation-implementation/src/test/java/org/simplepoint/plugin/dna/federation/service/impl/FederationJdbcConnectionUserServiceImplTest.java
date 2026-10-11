package org.simplepoint.plugin.dna.federation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.simplepoint.plugin.dna.federation.service.support.BaseServiceSchemaTestSupport.stubBaseServiceSchema;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.dna.federation.service.support.AuthorizedCrudExtension;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.federation.api.entity.FederationJdbcConnectionUser;
import org.simplepoint.plugin.dna.federation.api.pojo.dto.FederationJdbcUserDataSourceAssignDto;
import org.simplepoint.plugin.dna.federation.api.repository.FederationJdbcConnectionUserRepository;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.security.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith({MockitoExtension.class, AuthorizedCrudExtension.class})
class FederationJdbcConnectionUserServiceImplTest {

  @Mock
  private FederationJdbcConnectionUserRepository repository;

  @Mock
  private DetailsProviderService detailsProviderService;

  @Mock
  private JdbcDataSourceDefinitionService dataSourceService;

  @Mock
  private UsersService usersService;

  @Mock
  private EntityManager entityManager;

  @BeforeEach
  void setUp() {
    stubBaseServiceSchema(detailsProviderService);
  }

  @Test
  void shortUserSearchNeverEnumeratesTheDirectoryOrCallsTheRemoteUserService() {
    assertThat(service().searchUserOptions("al", PageRequest.of(0, 200)).getContent()).isEmpty();
    org.mockito.Mockito.verifyNoInteractions(entityManager, usersService);
  }

  @Test
  void findEnabledGrantShouldReturnEnabledGrant() {
    FederationJdbcConnectionUser grant = enabledGrant("grant-1", "ds-1", "user-1");
    when(repository.findByCatalogIdAndUserIdAndDeletedAtIsNull("ds-1", "user-1"))
        .thenReturn(Optional.of(grant));

    Optional<FederationJdbcConnectionUser> result = service().findEnabledGrant("ds-1", "user-1");

    assertThat(result).isPresent();
    assertThat(result.get().getId()).isEqualTo("grant-1");
  }

  @Test
  void findEnabledGrantShouldReturnEmptyWhenGrantIsDisabled() {
    FederationJdbcConnectionUser grant = grantWithEnabled("grant-1", "ds-1", "user-1", false);
    when(repository.findByCatalogIdAndUserIdAndDeletedAtIsNull("ds-1", "user-1"))
        .thenReturn(Optional.of(grant));

    Optional<FederationJdbcConnectionUser> result = service().findEnabledGrant("ds-1", "user-1");

    assertThat(result).isEmpty();
  }

  @Test
  void findEnabledGrantShouldReturnEmptyWhenGrantNotFound() {
    when(repository.findByCatalogIdAndUserIdAndDeletedAtIsNull("ds-1", "user-1"))
        .thenReturn(Optional.empty());

    Optional<FederationJdbcConnectionUser> result = service().findEnabledGrant("ds-1", "user-1");

    assertThat(result).isEmpty();
  }

  @Test
  void findEnabledGrantShouldRejectNullCatalogId() {
    assertThrows(IllegalArgumentException.class, () -> service().findEnabledGrant(null, "user-1"));
  }

  @Test
  void authorizedShouldReturnDistinctCatalogIdsFromEnabledGrants() {
    User user = enabledUser("user-1");
    when(usersService.findById("user-1")).thenReturn(Optional.of(user));
    when(repository.findAllByUserIdAndDeletedAtIsNull("user-1"))
        .thenReturn(List.of(
            enabledGrant("g1", "ds-1", "user-1"),
            enabledGrant("g2", "ds-2", "user-1"),
            grantWithEnabled("g3", "ds-3", "user-1", false)
        ));

    Collection<String> result = service().authorized("user-1");

    assertThat(result).containsExactly("ds-1", "ds-2");
  }

  @Test
  void authorizedShouldRejectDisabledUser() {
    User user = disabledUser("user-1");
    when(usersService.findById("user-1")).thenReturn(Optional.of(user));

    assertThrows(IllegalArgumentException.class, () -> service().authorized("user-1"));
  }

  @Test
  void authorizedShouldFallbackWhenAuthorizationUserLookupReturnsNull() {
    User user = enabledUser("user-1");
    when(usersService.findByIdForAuthorization("user-1")).thenReturn(null);
    when(usersService.findById("user-1")).thenReturn(Optional.of(user));
    when(repository.findAllByUserIdAndDeletedAtIsNull("user-1"))
        .thenReturn(List.of(enabledGrant("g1", "ds-1", "user-1")));

    Collection<String> result = service().authorized("user-1");

    assertThat(result).containsExactly("ds-1");
  }

  @Test
  void authorizedShouldRejectNullUserLookupWithoutNpe() {
    when(usersService.findByIdForAuthorization("user-1")).thenReturn(null);
    when(usersService.findById("user-1")).thenReturn(null);

    assertThrows(IllegalArgumentException.class, () -> service().authorized("user-1"));
  }

  @Test
  void enabledGrantsShouldReturnOnlyEnabledGrantsForUser() {
    User user = enabledUser("user-1");
    when(usersService.findById("user-1")).thenReturn(Optional.of(user));
    when(repository.findAllByUserIdAndDeletedAtIsNull("user-1"))
        .thenReturn(List.of(
            enabledGrant("g1", "ds-1", "user-1"),
            grantWithEnabled("g2", "ds-2", "user-1", false)
        ));

    Collection<FederationJdbcConnectionUser> result = service().enabledGrants("user-1");

    assertThat(result).hasSize(1);
    assertThat(result.iterator().next().getId()).isEqualTo("g1");
  }

  @Test
  void authorizeShouldCreateNewGrantForUserAndDataSource() {
    JdbcDataSourceDefinition dataSource = enabledDataSource("ds-1", "pg", "PostgreSQL");
    User user = enabledUser("user-1");
    FederationJdbcConnectionUser saved = enabledGrant("g-new", "ds-1", "user-1");
    when(usersService.findById("user-1")).thenReturn(Optional.of(user));
    when(dataSourceService.findActiveById("ds-1")).thenReturn(Optional.of(dataSource));
    when(repository.findByCatalogIdAndUserIdAndDeletedAtIsNull("ds-1", "user-1"))
        .thenReturn(Optional.empty());
    when(repository.save(any(FederationJdbcConnectionUser.class))).thenReturn(saved);

    FederationJdbcUserDataSourceAssignDto dto = new FederationJdbcUserDataSourceAssignDto();
    dto.setUserId("user-1");
    dto.setDataSourceIds(Set.of("ds-1"));
    Collection<FederationJdbcConnectionUser> result = service().authorize(dto);

    assertThat(result).hasSize(1);
    verify(repository).save(any(FederationJdbcConnectionUser.class));
  }

  @Test
  void authorizeShouldActivateExistingDisabledGrant() {
    JdbcDataSourceDefinition dataSource = enabledDataSource("ds-1", "pg", "PostgreSQL");
    User user = enabledUser("user-1");
    FederationJdbcConnectionUser existing = grantWithEnabled("g1", "ds-1", "user-1", false);
    FederationJdbcConnectionUser activated = enabledGrant("g1", "ds-1", "user-1");
    when(usersService.findById("user-1")).thenReturn(Optional.of(user));
    when(repository.findByCatalogIdAndUserIdAndDeletedAtIsNull("ds-1", "user-1"))
        .thenReturn(Optional.of(existing));
    when(dataSourceService.findActiveById("ds-1")).thenReturn(Optional.of(dataSource));
    // For modifyById: repository.findByIdAndDeletedAtIsNull returns existing
    when(repository.findByIdAndDeletedAtIsNull("g1")).thenReturn(Optional.of(existing));
    when(repository.findById("g1")).thenReturn(Optional.of(existing));
    when(repository.updateById(any(FederationJdbcConnectionUser.class))).thenReturn(activated);

    FederationJdbcUserDataSourceAssignDto dto = new FederationJdbcUserDataSourceAssignDto();
    dto.setUserId("user-1");
    dto.setDataSourceIds(Set.of("ds-1"));
    Collection<FederationJdbcConnectionUser> result = service().authorize(dto);

    assertThat(result).hasSize(1);
    verify(repository).updateById(any(FederationJdbcConnectionUser.class));
    verify(repository, never()).save(any(FederationJdbcConnectionUser.class));
  }

  @Test
  void authorizeShouldReturnExistingActiveGrantWithoutModification() {
    User user = enabledUser("user-1");
    FederationJdbcConnectionUser existing = enabledGrant("g1", "ds-1", "user-1");
    when(usersService.findById("user-1")).thenReturn(Optional.of(user));
    when(repository.findByCatalogIdAndUserIdAndDeletedAtIsNull("ds-1", "user-1"))
        .thenReturn(Optional.of(existing));

    FederationJdbcUserDataSourceAssignDto dto = new FederationJdbcUserDataSourceAssignDto();
    dto.setUserId("user-1");
    dto.setDataSourceIds(Set.of("ds-1"));
    Collection<FederationJdbcConnectionUser> result = service().authorize(dto);

    assertThat(result).hasSize(1);
    verify(repository, never()).save(any());
    verify(repository, never()).updateById(any());
  }

  @Test
  void authorizeShouldReturnEmptyWhenNoDataSourceIdsProvided() {
    User user = enabledUser("user-1");
    when(usersService.findById("user-1")).thenReturn(Optional.of(user));

    FederationJdbcUserDataSourceAssignDto dto = new FederationJdbcUserDataSourceAssignDto();
    dto.setUserId("user-1");
    dto.setDataSourceIds(Set.of());
    Collection<FederationJdbcConnectionUser> result = service().authorize(dto);

    assertThat(result).isEmpty();
  }

  @Test
  void unauthorizedShouldRemoveGrantsForUserAndDataSourceIds() {
    User user = enabledUser("user-1");
    FederationJdbcConnectionUser grant = enabledGrant("g1", "ds-1", "user-1");
    when(usersService.findById("user-1")).thenReturn(Optional.of(user));
    when(repository.findAllByUserIdAndCatalogIdInAndDeletedAtIsNull("user-1", Set.of("ds-1")))
        .thenReturn(List.of(grant));
    when(repository.findAllByIds(List.of("g1"))).thenReturn(List.of(grant));

    FederationJdbcUserDataSourceAssignDto dto = new FederationJdbcUserDataSourceAssignDto();
    dto.setUserId("user-1");
    dto.setDataSourceIds(Set.of("ds-1"));
    service().unauthorized(dto);

    verify(repository).deleteByIds(List.of("g1"));
  }

  @Test
  void unauthorizedShouldDoNothingWhenNoDataSourceIdsProvided() {
    User user = enabledUser("user-1");
    when(usersService.findById("user-1")).thenReturn(Optional.of(user));

    FederationJdbcUserDataSourceAssignDto dto = new FederationJdbcUserDataSourceAssignDto();
    dto.setUserId("user-1");
    dto.setDataSourceIds(Set.of());
    service().unauthorized(dto);

    verify(repository, never()).findAllByUserIdAndCatalogIdInAndDeletedAtIsNull(anyString(), any());
    verify(repository, never()).deleteByIds(any());
  }

  @Test
  void createShouldValidateAndPersistGrant() {
    final JdbcDataSourceDefinition dataSource = enabledDataSource("ds-1", "pg", "PostgreSQL");
    final User user = enabledUser("user-1");
    FederationJdbcConnectionUser entity = new FederationJdbcConnectionUser();
    entity.setCatalogId("ds-1");
    entity.setUserId("user-1");
    entity.setOperationPermissions(Set.of("METADATA", "QUERY"));
    FederationJdbcConnectionUser saved = enabledGrant("g-new", "ds-1", "user-1");
    when(dataSourceService.findActiveById("ds-1")).thenReturn(Optional.of(dataSource));
    when(usersService.findById("user-1")).thenReturn(Optional.of(user));
    when(repository.findByCatalogIdAndUserIdAndDeletedAtIsNull("ds-1", "user-1"))
        .thenReturn(Optional.empty());
    when(repository.save(any(FederationJdbcConnectionUser.class))).thenReturn(saved);

    FederationJdbcConnectionUser result = service().create(entity);

    assertThat(result.getId()).isEqualTo("g-new");
    verify(repository).save(any(FederationJdbcConnectionUser.class));
  }

  @Test
  void createShouldRejectWhenDataSourceNotFound() {
    FederationJdbcConnectionUser entity = new FederationJdbcConnectionUser();
    entity.setCatalogId("ds-missing");
    entity.setUserId("user-1");
    entity.setOperationPermissions(Set.of("METADATA"));
    when(dataSourceService.findActiveById("ds-missing")).thenReturn(Optional.empty());

    assertThrows(IllegalArgumentException.class, () -> service().create(entity));
  }

  @Test
  void createShouldRejectWhenUserNotFound() {
    final JdbcDataSourceDefinition dataSource = enabledDataSource("ds-1", "pg", "PostgreSQL");
    FederationJdbcConnectionUser entity = new FederationJdbcConnectionUser();
    entity.setCatalogId("ds-1");
    entity.setUserId("user-missing");
    entity.setOperationPermissions(Set.of("METADATA"));
    when(dataSourceService.findActiveById("ds-1")).thenReturn(Optional.of(dataSource));
    when(usersService.findById("user-missing")).thenReturn(Optional.empty());

    assertThrows(IllegalArgumentException.class, () -> service().create(entity));
  }

  @Test
  void createShouldRejectDuplicateGrant() {
    final JdbcDataSourceDefinition dataSource = enabledDataSource("ds-1", "pg", "PostgreSQL");
    final User user = enabledUser("user-1");
    final FederationJdbcConnectionUser existing = enabledGrant("g1", "ds-1", "user-1");
    FederationJdbcConnectionUser entity = new FederationJdbcConnectionUser();
    entity.setCatalogId("ds-1");
    entity.setUserId("user-1");
    entity.setOperationPermissions(Set.of("METADATA"));
    when(dataSourceService.findActiveById("ds-1")).thenReturn(Optional.of(dataSource));
    when(usersService.findById("user-1")).thenReturn(Optional.of(user));
    when(repository.findByCatalogIdAndUserIdAndDeletedAtIsNull("ds-1", "user-1"))
        .thenReturn(Optional.of(existing));

    assertThrows(IllegalArgumentException.class, () -> service().create(entity));
  }

  @Test
  void modifyByIdShouldValidateAndUpdateGrant() {
    final JdbcDataSourceDefinition dataSource = enabledDataSource("ds-1", "pg", "PostgreSQL");
    final User user = enabledUser("user-1");
    final FederationJdbcConnectionUser current = enabledGrant("g1", "ds-1", "user-1");
    FederationJdbcConnectionUser patch = new FederationJdbcConnectionUser();
    patch.setId("g1");
    patch.setCatalogId("ds-1");
    patch.setUserId("user-1");
    patch.setOperationPermissions(Set.of("METADATA", "QUERY"));
    FederationJdbcConnectionUser updated = enabledGrant("g1", "ds-1", "user-1");
    when(repository.findByIdAndDeletedAtIsNull("g1")).thenReturn(Optional.of(current));
    when(dataSourceService.findActiveById("ds-1")).thenReturn(Optional.of(dataSource));
    when(usersService.findById("user-1")).thenReturn(Optional.of(user));
    when(repository.findByCatalogIdAndUserIdAndDeletedAtIsNull("ds-1", "user-1"))
        .thenReturn(Optional.of(current));
    when(repository.findById("g1")).thenReturn(Optional.of(current));
    when(repository.updateById(any(FederationJdbcConnectionUser.class))).thenReturn(updated);

    FederationJdbcConnectionUser result = service().modifyById(patch);

    assertThat(result.getId()).isEqualTo("g1");
    verify(repository).updateById(any(FederationJdbcConnectionUser.class));
  }

  @Test
  void modifyByIdShouldRejectNonExistentGrant() {
    FederationJdbcConnectionUser patch = new FederationJdbcConnectionUser();
    patch.setId("g-not-found");
    patch.setCatalogId("ds-1");
    patch.setUserId("user-1");
    when(repository.findByIdAndDeletedAtIsNull("g-not-found")).thenReturn(Optional.empty());

    assertThrows(IllegalArgumentException.class, () -> service().modifyById(patch));
  }

  @Test
  void limitShouldAddDeletedAtFilterAndDelegateToRepository() {
    Page<FederationJdbcConnectionUser> page = new PageImpl<>(List.of());
    when(repository.limit(any(), any())).thenReturn(page);

    Page<FederationJdbcConnectionUser> result = service().limit(null, PageRequest.of(0, 10));

    assertThat(result).isNotNull();
    verify(repository).limit(any(), any());
  }

  @Test
  void dataSourceItemsShouldReturnMappedItems() {
    JdbcDataSourceDefinition dataSource = enabledDataSource("ds-1", "pg", "PostgreSQL");
    Page<JdbcDataSourceDefinition> page = new PageImpl<>(List.of(dataSource));
    when(dataSourceService.assignmentCandidates(any(), org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(page);

    var result = service().dataSourceItems(Map.of(), PageRequest.of(0, 10));

    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().get(0).code()).isEqualTo("pg");
  }

  @Test
  void dataSourceItemsShouldExcludeAuthorizedAndFilterByKeyword() {
    JdbcDataSourceDefinition ds2 = enabledDataSource("ds-2", "mysql", "MySQL");
    Page<JdbcDataSourceDefinition> page = new PageImpl<>(List.of(ds2));
    when(dataSourceService.assignmentCandidates(any(), org.mockito.ArgumentMatchers.eq("user-1"), any()))
        .thenReturn(page);

    var result = service().dataSourceItems(
        Map.of("userId", "user-1", "keyword", "mysql"),
        PageRequest.of(0, 10)
    );

    assertThat(result.getTotalElements()).isEqualTo(1);
    assertThat(result.getContent()).extracting(item -> item.id()).containsExactly("ds-2");
    var filters = org.mockito.ArgumentCaptor.forClass(Map.class);
    verify(dataSourceService).assignmentCandidates(filters.capture(), org.mockito.ArgumentMatchers.eq("user-1"),
        org.mockito.ArgumentMatchers.eq(PageRequest.of(0, 10)));
    assertThat(filters.getValue()).doesNotContainKeys("userId", "keyword");
    var document = org.simplepoint.core.query.QueryDocument.parse((String) filters.getValue().get("$query"));
    assertThat(document.where().op()).isEqualTo("or");
    assertThat(document.where().children()).extracting(node -> node.field())
        .containsExactly("code", "name", "databaseProductName");
    org.mockito.Mockito.verifyNoInteractions(repository);
  }

  @Test
  void dataSourceItemsShouldNotRequireUserLookupWhenExcludingAuthorized() {
    JdbcDataSourceDefinition dataSource = enabledDataSource("ds-1", "pg", "PostgreSQL");
    when(dataSourceService.assignmentCandidates(any(), org.mockito.ArgumentMatchers.eq("user-1"), any()))
        .thenReturn(new PageImpl<>(List.of(dataSource)));

    var result = service().dataSourceItems(Map.of("userId", "user-1"), PageRequest.of(0, 10));

    assertThat(result.getContent()).extracting(item -> item.id()).containsExactly("ds-1");
    verify(usersService, never()).findByIdForAuthorization(anyString());
    verify(usersService, never()).findById(anyString());
    org.mockito.Mockito.verifyNoInteractions(repository);
  }

  @Test
  void dataSourceItemsKeepServerCountAndNeverRequestUnpagedCatalogs() {
    var dataSource = enabledDataSource("ds-501", "late", "PostgreSQL");
    var pageable = PageRequest.of(25, 20);
    when(dataSourceService.assignmentCandidates(any(), org.mockito.ArgumentMatchers.eq("user-1"),
        org.mockito.ArgumentMatchers.eq(pageable))).thenReturn(new PageImpl<>(List.of(dataSource), pageable, 521));
    var result = service().dataSourceItems(Map.of("userId", "user-1", "name", "like:late"), pageable);
    assertThat(result.getTotalElements()).isEqualTo(521);
    assertThat(result.getContent()).extracting(item -> item.id()).containsExactly("ds-501");
    verify(dataSourceService, never()).limit(any(), any());
  }

  @Test
  void dataSourceProjectionRejectsCredentialsAndHiddenFieldsBeforeDatabaseWork() {
    for (String field : List.of("jdbcUrl", "username", "password", "enabled")) {
      assertThrows(org.simplepoint.core.query.InvalidQueryException.class,
          () -> service().dataSourceItems(Map.of(field, "equals:secret"), PageRequest.of(0, 20)));
    }
    assertThrows(org.simplepoint.core.query.InvalidQueryException.class,
        () -> service().dataSourceItems(Map.of("$query",
            "{\"version\":1,\"where\":{\"op\":\"equals\",\"field\":\"jdbcUrl\",\"value\":\"secret\"}}"),
            PageRequest.of(0, 20)));
    org.simplepoint.core.AuthorizationContextHolder.getContext().setFieldPermissions(Map.of(
        JdbcDataSourceDefinition.class.getName() + "#name", "MASKED"));
    assertThrows(org.simplepoint.core.query.InvalidQueryException.class,
        () -> service().dataSourceItems(Map.of("name", "like:secret"), PageRequest.of(0, 20)));
    org.mockito.Mockito.verifyNoInteractions(dataSourceService, repository);
  }

  @Test
  void assignedDataSourcesUseFixedUserAndEnabledScopeOutsidePublicFilters() {
    when(usersService.findById("user-1")).thenReturn(Optional.of(enabledUser("user-1")));
    when(repository.limitScoped(any(), any(), any())).thenReturn(new PageImpl<>(List.of(
        enabledGrant("g1", "ds-1", "user-1")), PageRequest.of(0, 20), 520));
    var result = service().assignedDataSources("user-1",
        Map.of("userId", "user-1", "enabled", "equals:false", "catalogName", "like:pg"), PageRequest.of(0, 20));
    assertThat(result.getTotalElements()).isEqualTo(520);
    verify(repository).limitScoped(org.mockito.ArgumentMatchers.eq(Map.of("enabled", "equals:false",
        "catalogName", "like:pg")), org.mockito.ArgumentMatchers.eq(Map.of("userId", "equals:user-1",
        "enabled", "equals:true", "deletedAt", "is:null")), org.mockito.ArgumentMatchers.eq(PageRequest.of(0, 20)));
    verify(repository, never()).findAllByUserIdAndDeletedAtIsNull(anyString());
  }

  @Test
  void selectedDataSourceItemsShouldReturnOnlyEnabledDataSources() {
    JdbcDataSourceDefinition ds1 = enabledDataSource("ds-1", "pg", "PostgreSQL");
    when(dataSourceService.findActiveById("ds-1")).thenReturn(Optional.of(ds1));

    var result = service().selectedDataSourceItems(Set.of("ds-1"));

    assertThat(result).hasSize(1);
  }

  @Test
  void permissionsUpdateWritesOnlyTheAllowedFieldAndLeavesLoadedEntityUntouched() {
    var current = enabledGrant("g1", "ds-1", "user-1");
    current.setDescription("original description");
    current.setOperationPermissions(Set.of("METADATA", "QUERY"));
    when(repository.findByIdAndDeletedAtIsNull("g1")).thenReturn(Optional.of(current));
    when(repository.updateOperationPermissions(org.mockito.ArgumentMatchers.eq("g1"),
        org.mockito.ArgumentMatchers.eq(Set.of("QUERY")), any(),
        org.mockito.ArgumentMatchers.eq("dna-test-user"))).thenReturn(1);
    var changed = service().updateOperationPermissions("g1", Set.of("query"));
    assertThat(changed.getOperationPermissions()).containsExactly("QUERY");
    assertThat(current.getOperationPermissions()).containsExactlyInAnyOrder("METADATA", "QUERY");
    assertThat(changed.getDescription()).isEqualTo("original description");
    verify(repository, never()).updateById(any());
    org.mockito.Mockito.verifyNoInteractions(dataSourceService, usersService);
  }

  @Test
  void permissionsUpdateRejectsNonWritableFieldsBeforeLoadingOrMutatingGrant() {
    org.simplepoint.core.AuthorizationContextHolder.getContext().setFieldPermissions(Map.of(
        FederationJdbcConnectionUser.class.getName() + "#operationPermissions", "READ_ONLY"));
    assertThrows(org.springframework.security.access.AccessDeniedException.class,
        () -> service().updateOperationPermissions("g1", Set.of("QUERY")));
    org.mockito.Mockito.verifyNoInteractions(repository);
  }

  @Test
  void permissionsUpdateAndIdempotentAuthorizeCannotBypassGrantDataScope() {
    var current = enabledGrant("g1", "ds-1", "user-1");
    current.setOperationPermissions(Set.of("METADATA", "QUERY"));
    var denied = new org.simplepoint.core.AuthorizationContext();
    denied.setUserId("dna-test-user");
    denied.setDataScopeType("NONE");
    org.simplepoint.core.RequestContextHolder.setContext(
        org.simplepoint.core.RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, denied);
    when(repository.findByIdAndDeletedAtIsNull("g1")).thenReturn(Optional.of(current));
    assertThrows(org.springframework.security.access.AccessDeniedException.class,
        () -> service().updateOperationPermissions("g1", Set.of("QUERY")));
    when(usersService.findById("user-1")).thenReturn(Optional.of(enabledUser("user-1")));
    when(repository.findByCatalogIdAndUserIdAndDeletedAtIsNull("ds-1", "user-1"))
        .thenReturn(Optional.of(current));
    var dto = new FederationJdbcUserDataSourceAssignDto();
    dto.setUserId("user-1");
    dto.setDataSourceIds(Set.of("ds-1"));
    assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> service().authorize(dto));
    verify(repository, never()).updateOperationPermissions(any(), any(), any(), any());
    assertThat(current.getOperationPermissions()).containsExactlyInAnyOrder("METADATA", "QUERY");
  }

  @Test
  void invalidPermissionsAndOversizedIdBatchesFailBeforeWrites() {
    var current = enabledGrant("g1", "ds-1", "user-1");
    when(repository.findByIdAndDeletedAtIsNull("g1")).thenReturn(Optional.of(current));
    assertThrows(IllegalArgumentException.class, () -> service().updateOperationPermissions("g1", Set.of()));
    assertThrows(IllegalArgumentException.class, () -> service().updateOperationPermissions("g1", Set.of("DROP_ALL")));
    assertThrows(IllegalArgumentException.class, () -> service().selectedDataSourceItems(
        java.util.stream.IntStream.range(0, 101).mapToObj(index -> "ds-" + index).toList()));
    verify(repository, never()).updateOperationPermissions(any(), any(), any(), any());
    org.mockito.Mockito.verifyNoInteractions(dataSourceService);
  }

  @Test
  void selectedDataSourceItemsShouldReturnEmptyForNullInput() {
    var result = service().selectedDataSourceItems(null);
    assertThat(result).isEmpty();
  }

  private FederationJdbcConnectionUserServiceImpl service() {
    return new FederationJdbcConnectionUserServiceImpl(
        repository, detailsProviderService, dataSourceService, usersService, entityManager
    );
  }

  private static User enabledUser(final String id) {
    User user = new User();
    user.setId(id);
    user.setEnabled(true);
    user.setEmail(id + "@example.com");
    user.setNickname("User " + id);
    return user;
  }

  private static User disabledUser(final String id) {
    User user = new User();
    user.setId(id);
    user.setEnabled(false);
    return user;
  }

  private static JdbcDataSourceDefinition enabledDataSource(
      final String id,
      final String code,
      final String dbProductName
  ) {
    JdbcDataSourceDefinition ds = new JdbcDataSourceDefinition();
    ds.setId(id);
    ds.setCode(code);
    ds.setName("DataSource " + code);
    ds.setEnabled(true);
    ds.setDatabaseProductName(dbProductName);
    return ds;
  }

  private static FederationJdbcConnectionUser enabledGrant(
      final String id,
      final String catalogId,
      final String userId
  ) {
    return grantWithEnabled(id, catalogId, userId, true);
  }

  private static FederationJdbcConnectionUser grantWithEnabled(
      final String id,
      final String catalogId,
      final String userId,
      final boolean enabled
  ) {
    FederationJdbcConnectionUser grant = new FederationJdbcConnectionUser();
    grant.setId(id);
    grant.setCatalogId(catalogId);
    grant.setUserId(userId);
    grant.setEnabled(enabled);
    grant.setOperationPermissions(Set.of("METADATA", "QUERY"));
    return grant;
  }
}
