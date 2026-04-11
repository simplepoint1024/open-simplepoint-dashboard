package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.normalizeLikeQuery;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.requireEntityId;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.requireValue;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.trimToNull;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.federation.api.constants.FederationJdbcOperation;
import org.simplepoint.plugin.dna.federation.api.entity.FederationJdbcConnectionUser;
import org.simplepoint.plugin.dna.federation.api.pojo.dto.FederationJdbcUserDataSourceAssignDto;
import org.simplepoint.plugin.dna.federation.api.repository.FederationJdbcConnectionUserRepository;
import org.simplepoint.plugin.dna.federation.api.service.FederationJdbcConnectionUserService;
import org.simplepoint.plugin.dna.federation.api.vo.FederationJdbcUserDataSourceItemVo;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.security.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC connection-user maintenance service implementation.
 */
@Service
public class FederationJdbcConnectionUserServiceImpl
    extends BaseServiceImpl<FederationJdbcConnectionUserRepository, FederationJdbcConnectionUser, String>
    implements FederationJdbcConnectionUserService {

  private final FederationJdbcConnectionUserRepository repository;

  private final JdbcDataSourceDefinitionService dataSourceService;

  private final UsersService usersService;

  /**
   * Creates a JDBC connection-user service implementation.
   *
   * @param repository repository
   * @param detailsProviderService details provider
   * @param dataSourceService datasource service
   * @param usersService users service
   */
  public FederationJdbcConnectionUserServiceImpl(
      final FederationJdbcConnectionUserRepository repository,
      final DetailsProviderService detailsProviderService,
      final JdbcDataSourceDefinitionService dataSourceService,
      final UsersService usersService
  ) {
    super(repository, detailsProviderService);
    this.repository = repository;
    this.dataSourceService = dataSourceService;
    this.usersService = usersService;
  }

  @Override
  public <S extends FederationJdbcConnectionUser> Page<S> limit(
      final Map<String, String> attributes,
      final Pageable pageable
  ) {
    Map<String, String> normalized = new LinkedHashMap<>();
    if (attributes != null) {
      normalized.putAll(attributes);
    }
    normalized.put("deletedAt", "is:null");
    normalizeLikeQuery(normalized, "catalogName");
    normalizeLikeQuery(normalized, "catalogCode");
    normalizeLikeQuery(normalized, "userDisplayName");
    normalizeLikeQuery(normalized, "userEmail");
    normalizeLikeQuery(normalized, "userPhoneNumber");
    return super.limit(normalized, pageable);
  }

  @Override
  public Optional<FederationJdbcConnectionUser> findEnabledGrant(final String catalogId, final String userId) {
    return repository.findByCatalogIdAndUserIdAndDeletedAtIsNull(
            requireValue(catalogId, "数据源ID不能为空"),
            requireValue(userId, "用户ID不能为空")
        )
        .filter(grant -> Boolean.TRUE.equals(grant.getEnabled()));
  }

  @Override
  public Page<FederationJdbcUserDataSourceItemVo> dataSourceItems(final Pageable pageable) {
    return dataSourceService.limit(Map.of("enabled", "equals:true"), pageable)
        .map(FederationJdbcConnectionUserServiceImpl::toDataSourceItem);
  }

  @Override
  public Collection<FederationJdbcUserDataSourceItemVo> selectedDataSourceItems(final Collection<String> dataSourceIds) {
    if (dataSourceIds == null || dataSourceIds.isEmpty()) {
      return List.of();
    }
    return normalizeDataSourceIds(dataSourceIds).stream()
        .map(dataSourceService::findActiveById)
        .flatMap(Optional::stream)
        .filter(dataSource -> Boolean.TRUE.equals(dataSource.getEnabled()))
        .map(FederationJdbcConnectionUserServiceImpl::toDataSourceItem)
        .toList();
  }

  @Override
  public Collection<String> authorized(final String userId) {
    requireActiveUser(userId);
    return enabledGrants(userId).stream()
        .map(FederationJdbcConnectionUser::getCatalogId)
        .filter(value -> value != null && !value.isBlank())
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
  }

  @Override
  public Collection<FederationJdbcConnectionUser> enabledGrants(final String userId) {
    requireActiveUser(userId);
    return repository.findAllByUserIdAndDeletedAtIsNull(requireValue(userId, "系统用户ID不能为空")).stream()
        .filter(grant -> Boolean.TRUE.equals(grant.getEnabled()))
        .toList();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public Collection<FederationJdbcConnectionUser> authorize(final FederationJdbcUserDataSourceAssignDto dto) {
    User user = requireActiveUser(dto == null ? null : dto.getUserId());
    Set<String> dataSourceIds = normalizeDataSourceIds(dto == null ? null : dto.getDataSourceIds());
    if (dataSourceIds.isEmpty()) {
      return List.of();
    }
    List<FederationJdbcConnectionUser> grants = new java.util.ArrayList<>(dataSourceIds.size());
    for (String dataSourceId : dataSourceIds) {
      Optional<FederationJdbcConnectionUser> existing = repository.findByCatalogIdAndUserIdAndDeletedAtIsNull(dataSourceId, user.getId());
      if (existing.isPresent()) {
        FederationJdbcConnectionUser current = existing.get();
        if (Boolean.TRUE.equals(current.getEnabled())) {
          grants.add(current);
          continue;
        }
        FederationJdbcConnectionUser patch = new FederationJdbcConnectionUser();
        patch.setId(current.getId());
        patch.setCatalogId(dataSourceId);
        patch.setUserId(user.getId());
        patch.setEnabled(true);
        patch.setOperationPermissions(current.getOperationPermissions());
        patch.setDescription(current.getDescription());
        grants.add(modifyById(patch));
        continue;
      }
      FederationJdbcConnectionUser grant = new FederationJdbcConnectionUser();
      grant.setCatalogId(dataSourceId);
      grant.setUserId(user.getId());
      grant.setEnabled(true);
      grant.setOperationPermissions(FederationJdbcOperation.readOnlyDefaults());
      grants.add(create(grant));
    }
    return grants;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void unauthorized(final FederationJdbcUserDataSourceAssignDto dto) {
    User user = requireActiveUser(dto == null ? null : dto.getUserId());
    Set<String> dataSourceIds = normalizeDataSourceIds(dto == null ? null : dto.getDataSourceIds());
    if (dataSourceIds.isEmpty()) {
      return;
    }
    List<String> grantIds = repository.findAllByUserIdAndCatalogIdInAndDeletedAtIsNull(user.getId(), dataSourceIds).stream()
        .map(FederationJdbcConnectionUser::getId)
        .filter(value -> value != null && !value.isBlank())
        .toList();
    if (!grantIds.isEmpty()) {
      super.removeByIds(grantIds);
    }
  }

  @Override
  public <S extends FederationJdbcConnectionUser> S create(final S entity) {
    normalizeAndValidate(entity, null, null);
    if (entity.getEnabled() == null) {
      entity.setEnabled(true);
    }
    return super.create(entity);
  }

  @Override
  public <S extends FederationJdbcConnectionUser> FederationJdbcConnectionUser modifyById(final S entity) {
    FederationJdbcConnectionUser current = repository.findByIdAndDeletedAtIsNull(requireEntityId(entity))
        .orElseThrow(() -> new IllegalArgumentException("JDBC 连接用户不存在: " + entity.getId()));
    normalizeAndValidate(entity, current.getId(), current);
    if (entity.getEnabled() == null) {
      entity.setEnabled(current.getEnabled());
    }
    return (FederationJdbcConnectionUser) super.modifyById(entity);
  }

  private void normalizeAndValidate(
      final FederationJdbcConnectionUser entity,
      final String currentId,
      final FederationJdbcConnectionUser current
  ) {
    if (entity == null) {
      throw new IllegalArgumentException("JDBC 连接用户不能为空");
    }
    JdbcDataSourceDefinition dataSource = dataSourceService.findActiveById(requireValue(
            entity.getCatalogId(),
            "数据源ID不能为空"
        ))
        .filter(item -> Boolean.TRUE.equals(item.getEnabled()))
        .orElseThrow(() -> new IllegalArgumentException("数据源不存在或未启用: " + entity.getCatalogId()));
    User user = usersService.findById(requireValue(entity.getUserId(), "系统用户ID不能为空"))
        .orElseThrow(() -> new IllegalArgumentException("系统用户不存在: " + entity.getUserId()));
    if (!Boolean.TRUE.equals(user.getEnabled())) {
      throw new IllegalArgumentException("系统用户已禁用: " + entity.getUserId());
    }
    repository.findByCatalogIdAndUserIdAndDeletedAtIsNull(dataSource.getId(), user.getId())
        .filter(existing -> currentId == null || !existing.getId().equals(currentId))
        .ifPresent(existing -> {
          throw new IllegalArgumentException("该系统用户已配置当前数据源的 JDBC 连接权限");
        });

    entity.setCatalogId(dataSource.getId());
    entity.setCatalogName(dataSource.getName());
    entity.setCatalogCode(dataSource.getCode());
    entity.setUserId(user.getId());
    entity.setUserDisplayName(resolveUserDisplayName(user));
    entity.setUserEmail(trimToNull(user.getEmail()));
    entity.setUserPhoneNumber(trimToNull(user.getPhoneNumber()));
    entity.setOperationPermissions(resolveOperationPermissions(entity.getOperationPermissions(), current));
    entity.setDescription(trimToNull(entity.getDescription()));
  }

  private static Set<String> resolveOperationPermissions(
      final Set<String> configuredValues,
      final FederationJdbcConnectionUser current
  ) {
    Set<String> values = configuredValues;
    if (values == null) {
      values = current == null ? FederationJdbcOperation.readOnlyDefaults() : current.getOperationPermissions();
    }
    Set<String> normalized = FederationJdbcOperation.normalizeCodes(values);
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("至少需要配置一个 JDBC 操作权限");
    }
    return normalized;
  }

  private static String resolveUserDisplayName(final User user) {
    if (user == null) {
      return "";
    }
    String nickname = trimToNull(user.getNickname());
    if (nickname != null) {
      return nickname;
    }
    String name = trimToNull(user.getName());
    if (name != null) {
      return name;
    }
    String givenName = trimToNull(user.getGivenName());
    String familyName = trimToNull(user.getFamilyName());
    String combinedName = trimToNull(
        (givenName == null ? "" : givenName) + (familyName == null ? "" : " " + familyName)
    );
    if (combinedName != null) {
      return combinedName;
    }
    String email = trimToNull(user.getEmail());
    if (email != null) {
      return email;
    }
    String phoneNumber = trimToNull(user.getPhoneNumber());
    if (phoneNumber != null) {
      return phoneNumber;
    }
    return requireValue(user.getId(), "系统用户ID不能为空");
  }

  private User requireActiveUser(final String userId) {
    User user = usersService.findById(requireValue(userId, "系统用户ID不能为空"))
        .orElseThrow(() -> new IllegalArgumentException("系统用户不存在: " + userId));
    if (!Boolean.TRUE.equals(user.getEnabled())) {
      throw new IllegalArgumentException("系统用户已禁用: " + userId);
    }
    return user;
  }

  private static Set<String> normalizeDataSourceIds(final Collection<String> dataSourceIds) {
    if (dataSourceIds == null || dataSourceIds.isEmpty()) {
      return Set.of();
    }
    return dataSourceIds.stream()
        .map(value -> trimToNull(value))
        .filter(value -> value != null && !value.isBlank())
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
  }

  private static FederationJdbcUserDataSourceItemVo toDataSourceItem(final JdbcDataSourceDefinition dataSource) {
    return new FederationJdbcUserDataSourceItemVo(
        dataSource == null ? null : dataSource.getId(),
        dataSource == null ? null : dataSource.getCode(),
        dataSource == null ? null : dataSource.getName(),
        dataSource == null ? null : dataSource.getDatabaseProductName()
    );
  }
}
