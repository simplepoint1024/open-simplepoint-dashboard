package org.simplepoint.plugin.dna.federation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

@ExtendWith(MockitoExtension.class)
class FederationJdbcConnectionUserServiceImplTest {

  @Mock
  private FederationJdbcConnectionUserRepository repository;

  @Mock
  private DetailsProviderService detailsProviderService;

  @Mock
  private JdbcDataSourceDefinitionService dataSourceService;

  @Mock
  private UsersService usersService;

  @BeforeEach
  void setUp() {
    lenient().when(detailsProviderService.getDialects(any())).thenReturn(List.of());
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
    when(repository.findById("g1")).thenReturn(Optional.empty());
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
    JdbcDataSourceDefinition dataSource = enabledDataSource("ds-1", "pg", "PostgreSQL");
    User user = enabledUser("user-1");
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
    JdbcDataSourceDefinition dataSource = enabledDataSource("ds-1", "pg", "PostgreSQL");
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
    JdbcDataSourceDefinition dataSource = enabledDataSource("ds-1", "pg", "PostgreSQL");
    User user = enabledUser("user-1");
    FederationJdbcConnectionUser existing = enabledGrant("g1", "ds-1", "user-1");
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
    JdbcDataSourceDefinition dataSource = enabledDataSource("ds-1", "pg", "PostgreSQL");
    User user = enabledUser("user-1");
    FederationJdbcConnectionUser current = enabledGrant("g1", "ds-1", "user-1");
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
    when(repository.findById("g1")).thenReturn(Optional.empty());
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
    when(dataSourceService.limit(any(), any())).thenReturn(page);

    var result = service().dataSourceItems(PageRequest.of(0, 10));

    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().get(0).code()).isEqualTo("pg");
  }

  @Test
  void selectedDataSourceItemsShouldReturnOnlyEnabledDataSources() {
    JdbcDataSourceDefinition ds1 = enabledDataSource("ds-1", "pg", "PostgreSQL");
    when(dataSourceService.findActiveById("ds-1")).thenReturn(Optional.of(ds1));

    var result = service().selectedDataSourceItems(Set.of("ds-1"));

    assertThat(result).hasSize(1);
  }

  @Test
  void selectedDataSourceItemsShouldReturnEmptyForNullInput() {
    var result = service().selectedDataSourceItems(null);
    assertThat(result).isEmpty();
  }

  private FederationJdbcConnectionUserServiceImpl service() {
    return new FederationJdbcConnectionUserServiceImpl(
        repository, detailsProviderService, dataSourceService, usersService
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
