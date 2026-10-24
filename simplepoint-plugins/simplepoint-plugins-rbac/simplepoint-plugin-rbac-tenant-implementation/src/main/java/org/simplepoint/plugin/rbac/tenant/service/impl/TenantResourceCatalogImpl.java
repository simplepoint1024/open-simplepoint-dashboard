package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.simplepoint.core.query.TablePagination;
import org.simplepoint.plugin.rbac.tenant.api.entity.Tenant;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceKey;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceOrigin;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceSetup;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantResourceSetupException;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceCatalog;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceProvisioningService;
import org.simplepoint.security.entity.ResourceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Transactional tenant catalog. Platform resource IDs remain stable in each tenant namespace. */
@Service
@Transactional(readOnly = true)
public class TenantResourceCatalogImpl implements TenantResourceCatalog {
  private final EntityManager em;
  private final TenantRepository tenants;
  private final TenantResourceProvisioningService provisioning;

  /**
   * Directory management shares initialization/synchronization with ordinary tenant provisioning.
   */
  public TenantResourceCatalogImpl(
      EntityManager em, TenantRepository tenants, TenantResourceProvisioningService provisioning) {
    this.em = em;
    this.tenants = tenants;
    this.provisioning = provisioning;
  }

  @Override
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public void lockForGrantModification(String tenantId) {
    if (tenantId == null
        || tenantId.isBlank()
        || em.createQuery(
                "select t.id from Tenant t where t.id = :tenant and t.deletedAt is null",
                String.class)
            .setParameter("tenant", tenantId)
            .setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
            .getResultList()
            .isEmpty()) {
      throw new org.springframework.security.access.AccessDeniedException("Tenant unavailable");
    }
    new TenantResourceReadQuery(em, tenantId).requireReady();
  }

  @Override
  @Transactional
  public SyncResult synchronize(boolean overwrite) {
    return provisioning.synchronize(overwrite);
  }

  @Override
  public SyncPreview previewSynchronization(boolean overwrite) {
    return provisioning.preview(overwrite);
  }

  @Override
  public Page<TenantResource> list(
      String keyword, String parentId, boolean rootsOnly, Pageable pageable) {
    return query(
        keyword == null ? Map.of() : Map.of("keyword", keyword), parentId, rootsOnly, pageable);
  }

  @Override
  public Map<String, Object> querySchema() {
    managerTenant();
    var result =
        new java.util.LinkedHashMap<>(
            org.simplepoint.core.schema.ProjectionTableSchema.schema(
                org.simplepoint.plugin.rbac.tenant.api.model.TenantResourceQuery.class,
                "tenant.resources",
                Map.of(
                    "type",
                        java.util.Arrays.stream(ResourceType.values())
                            .map(
                                value ->
                                    new org.simplepoint.core.schema.ProjectionTableSchema.Choice(
                                        value.name(),
                                        "i18n:tenant.resources."
                                            + value.name().toLowerCase(java.util.Locale.ROOT)))
                            .toList(),
                    "origin",
                        java.util.Arrays.stream(TenantResourceOrigin.values())
                            .map(
                                value ->
                                    new org.simplepoint.core.schema.ProjectionTableSchema.Choice(
                                        value.name(), "i18n:" + value.messageKey()))
                            .toList())));
    result.put(
        "buttons",
        List.of(
            Map.of(
                "key",
                "sync",
                "title",
                "i18n:tenant.resources.checkUpdates",
                "icon",
                "SyncOutlined",
                "sort",
                0,
                "argumentMinSize",
                0,
                "argumentMaxSize",
                0),
            Map.of(
                "key",
                "overwrite",
                "title",
                "i18n:tenant.resources.restorePresentation",
                "icon",
                "UndoOutlined",
                "sort",
                1,
                "argumentMinSize",
                0,
                "argumentMaxSize",
                0),
            Map.of(
                "key",
                "add",
                "title",
                "i18n:tenant.resources.create",
                "icon",
                "PlusOutlined",
                "sort",
                2,
                "argumentMinSize",
                0,
                "argumentMaxSize",
                0),
            Map.of(
                "key",
                "edit",
                "title",
                "i18n:table.button.edit",
                "icon",
                "EditOutlined",
                "sort",
                3,
                "argumentMinSize",
                1,
                "argumentMaxSize",
                1),
            Map.of(
                "key",
                "delete",
                "title",
                "i18n:action.delete",
                "icon",
                "DeleteOutlined",
                "sort",
                4,
                "argumentMinSize",
                1,
                "argumentMaxSize",
                1,
                "danger",
                true)));
    TenantResourceAvailabilityProjector.appendSchema(result);
    appendLocalizedNameSchema(result);
    return result;
  }

  @SuppressWarnings("unchecked")
  private static void appendLocalizedNameSchema(Map<String, Object> result) {
    String access = org.simplepoint.core.jackson.FieldPermissionPolicy.access(TenantResource.class, "name");
    if (org.simplepoint.core.jackson.FieldPermissionPolicy.hidden(access) || "MASKED".equals(access)) return;
    try {
      var declaration = TenantResource.class.getDeclaredField("nameLocalization")
          .getAnnotation(org.simplepoint.api.schema.LocalizedField.class);
      var field = new java.util.LinkedHashMap<>(org.simplepoint.api.schema.LocalizedFieldSchema.describe(declaration));
      field.put("title", "i18n:tenant.resources.name");
      field.put("x-ui", Map.of("ui:field", "LocalizedText", "x-list-visible", false));
      if (access != null) { field.put("x-field-access", access); field.put("readOnly", !"EDITABLE".equals(access)); }
      var schema = (Map<String, Object>) result.get("schema");
      ((Map<String, Object>) schema.get("properties")).put("nameLocalization", field);
    } catch (NoSuchFieldException error) { throw new IllegalStateException(error); }
  }

  @Override
  public Page<TenantResource> query(
      Map<String, String> parameters, String parentId, boolean rootsOnly, Pageable pageable) {
    final Tenant tenant = managerTenant();
    if (pageable == null || pageable.isUnpaged() || pageable.getPageSize() > TablePagination.MAX_PAGE_SIZE) {
      throw badRequest("每页最多 100 条资源");
    }
    var filters =
        new java.util.LinkedHashMap<>(parameters == null ? Map.<String, String>of() : parameters);
    String keyword = filters.remove("keyword");
    filters.remove("parentId");
    filters.remove("rootsOnly");
    final Class<?> queryType =
        org.simplepoint.plugin.rbac.tenant.api.model.TenantResourceQuery.class;
    String search = keyword == null ? "" : keyword.trim().toLowerCase(java.util.Locale.ROOT);
    if (rootsOnly && parentId != null) {
      throw badRequest("根资源与上级资源筛选不能同时使用");
    }
    if (search.length() > 128) {
      throw badRequest("搜索词过长");
    }
    if (pageable.getOffset() > Integer.MAX_VALUE) {
      throw badRequest("分页范围过大");
    }
    if (!search.isEmpty()) {
      org.simplepoint.core.query.QueryCapabilities.require(queryType, "name", "like", false);
      org.simplepoint.core.query.QueryCapabilities.require(queryType, "code", "like", false);
    }
    if (pageable.getSort().isUnsorted()) {
      var defaults =
          java.util.stream.Stream.of("sort", "name")
              .filter(
                  field -> org.simplepoint.core.query.QueryCapabilities.readable(queryType, field))
              .map(org.springframework.data.domain.Sort.Order::asc)
              .toList();
      pageable =
          org.springframework.data.domain.PageRequest.of(
              pageable.getPageNumber(),
              pageable.getPageSize(),
              org.springframework.data.domain.Sort.by(defaults));
    }
    var input =
        org.simplepoint.data.jpa.ProjectionQuery.prepareIgnoringCase(
            queryType, filters, pageable, Set.of("name", "code"));
    var cb = em.getCriteriaBuilder();
    var query = cb.createQuery(TenantResource.class);
    var root = query.from(TenantResource.class);
    query
        .select(root)
        .where(
            cb.and(
                listFilter(cb, root, tenant.getId(), search, parentId, rootsOnly),
                input.predicate(cb, queryFields(cb, root))));
    query.orderBy(input.orders(cb, queryFields(cb, root), root.get("id")));
    List<TenantResource> content =
        em.createQuery(query)
            .setFirstResult((int) pageable.getOffset())
            .setMaxResults(pageable.getPageSize())
            .getResultList();
    var count = cb.createQuery(Long.class);
    var counted = count.from(TenantResource.class);
    count
        .select(cb.count(counted))
        .where(
            cb.and(
                listFilter(cb, counted, tenant.getId(), search, parentId, rootsOnly),
                input.predicate(cb, queryFields(cb, counted))));
    return new PageImpl<>(
        new TenantResourceAvailabilityProjector(em, tenant.getId(), Instant.now()).project(content),
        pageable,
        em.createQuery(count).getSingleResult());
  }

  private Map<String, jakarta.persistence.criteria.Expression<?>> queryFields(
      jakarta.persistence.criteria.CriteriaBuilder cb,
      jakarta.persistence.criteria.Root<TenantResource> root) {
    var origin =
        cb.<String>selectCase()
            .when(
                cb.isNotNull(root.get("platformResourceId")), TenantResourceOrigin.PLATFORM.name());
    for (TenantResourceOrigin source : TenantResourceOrigin.values()) {
      if (source.managedOwner() != null) {
        origin.when(cb.equal(root.get("managedOwner"), source.managedOwner()), source.name());
      }
    }
    origin.when(cb.isNull(root.get("managedOwner")), TenantResourceOrigin.TENANT.name());
    return Map.of(
        "name",
        root.get("name"),
        "code",
        root.get("code"),
        "type",
        root.get("type"),
        "enabled",
        root.get("enabled"),
        "sort",
        root.get("sort"),
        "createdAt",
        root.get("createdAt"),
        "updatedAt",
        root.get("updatedAt"),
        "lastSyncedAt",
        root.get("lastSyncedAt"),
        "origin",
        origin.otherwise(cb.nullLiteral(String.class)));
  }

  @Override
  public TenantResource get(String id) {
    Tenant tenant = managerTenant();
    TenantResource resource = find(tenant.getId(), id);
    if (resource == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
    }
    return new TenantResourceAvailabilityProjector(em, tenant.getId(), Instant.now())
        .project(List.of(resource))
        .getFirst();
  }

  @Override
  public Page<TenantResource> picker(
      PickerKind kind,
      ResourceType childType,
      String excludedId,
      String keyword,
      String selectedId,
      Pageable pageable) {
    final Tenant tenant = managerTenant();
    if (kind == null || kind == PickerKind.PARENT && childType == null) {
      throw badRequest("候选项类型无效");
    }
    if (pageable == null
        || pageable.isUnpaged()
        || pageable.getPageSize() > 50
        || pageable.getSort().isSorted()
        || pageable.getOffset() > Integer.MAX_VALUE) {
      throw badRequest("候选项每页最多 50 条");
    }
    for (String id : new String[] {excludedId, selectedId}) {
      if (id != null && (id.isBlank() || id.length() > 36 || !id.equals(id.trim()))) {
        throw badRequest("资源 ID 无效");
      }
    }
    String search = keyword == null ? "" : keyword.trim().toLowerCase(java.util.Locale.ROOT);
    if (search.length() > 128) {
      throw badRequest("搜索词过长");
    }
    var eligible = new TenantResourceReadQuery(em, tenant.getId()).eligibleResourceIds();
    var cb = em.getCriteriaBuilder();
    var query = cb.createQuery(TenantResource.class);
    var root = query.from(TenantResource.class);
    query
        .select(root)
        .where(
            pickerFilter(
                cb,
                root,
                tenant.getId(),
                kind,
                childType,
                excludedId,
                search,
                selectedId,
                eligible));
    query.orderBy(cb.asc(root.get("sort")), cb.asc(root.get("name")), cb.asc(root.get("id")));
    var content =
        em.createQuery(query)
            .setFirstResult((int) pageable.getOffset())
            .setMaxResults(pageable.getPageSize())
            .getResultList();
    var count = cb.createQuery(Long.class);
    var counted = count.from(TenantResource.class);
    count
        .select(cb.count(counted))
        .where(
            pickerFilter(
                cb,
                counted,
                tenant.getId(),
                kind,
                childType,
                excludedId,
                search,
                selectedId,
                eligible));
    return new PageImpl<>(content, pageable, em.createQuery(count).getSingleResult());
  }

  private jakarta.persistence.criteria.Predicate pickerFilter(
      jakarta.persistence.criteria.CriteriaBuilder cb,
      jakarta.persistence.criteria.Root<TenantResource> root,
      String tenantId,
      PickerKind kind,
      ResourceType childType,
      String excludedId,
      String search,
      String selectedId,
      Set<String> eligible) {
    List<jakarta.persistence.criteria.Predicate> filters = new ArrayList<>();
    filters.add(listFilter(cb, root, tenantId, search, null, false));
    var ids = new ArrayList<>(eligible);
    List<jakarta.persistence.criteria.Predicate> allowed = new ArrayList<>();
    for (int start = 0; start < ids.size(); start += 100) {
      allowed.add(root.get("id").in(ids.subList(start, Math.min(start + 100, ids.size()))));
    }
    filters.add(cb.or(allowed.toArray(jakarta.persistence.criteria.Predicate[]::new)));
    filters.add(cb.isTrue(root.get("enabled")));
    filters.add(cb.isFalse(root.get("manualDisabled")));
    filters.add(cb.isFalse(root.get("formallyOffline")));
    filters.add(cb.isTrue(root.get("sourceAvailable")));
    if (excludedId != null) {
      filters.add(cb.notEqual(root.get("id"), excludedId));
    }
    if (selectedId != null) {
      filters.add(cb.equal(root.get("id"), selectedId));
    }
    if (kind == PickerKind.PARENT) {
      filters.add(cb.not(root.get("type").in(ResourceType.ACTION, ResourceType.API)));
      if (isNavigationType(childType)) {
        filters.add(
            root.get("type").in(ResourceType.GROUP, ResourceType.MODULE, ResourceType.PAGE));
      }
    } else {
      filters.add(cb.equal(root.get("type"), ResourceType.PAGE));
      filters.add(cb.isTrue(root.get("grantable")));
      filters.add(cb.isNotNull(root.get("platformResourceId")));
      filters.add(
          cb.or(
              cb.isNull(root.get("routeKind")),
              cb.notEqual(cb.lower(root.get("routeKind")), "hidden")));
    }
    return cb.and(filters.toArray(jakarta.persistence.criteria.Predicate[]::new));
  }

  private jakarta.persistence.criteria.Predicate listFilter(
      jakarta.persistence.criteria.CriteriaBuilder cb,
      jakarta.persistence.criteria.Root<TenantResource> root,
      String tenantId,
      String search,
      String parentId,
      boolean rootsOnly) {
    List<jakarta.persistence.criteria.Predicate> filters = new ArrayList<>();
    filters.add(cb.equal(root.get("tenantId"), tenantId));
    if (parentId != null) {
      filters.add(cb.equal(root.get("parentId"), parentId));
    } else if (rootsOnly) {
      filters.add(cb.isNull(root.get("parentId")));
    }
    if (!search.isEmpty()) {
      String pattern = "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
      filters.add(
          cb.or(
              cb.like(cb.lower(root.get("code")), pattern, '!'),
              cb.like(cb.lower(root.get("name")), pattern, '!')));
    }
    return cb.and(filters.toArray(jakarta.persistence.criteria.Predicate[]::new));
  }

  @Override
  @Transactional
  public TenantResource create(Mutation mutation) {
    Tenant tenant = lockedManagerTenant();
    validate(mutation);
    if (mutation.type() == ResourceType.ACTION
        || mutation.type() == ResourceType.API
        || mutation.type() == ResourceType.FEATURE) {
      throw badRequest("租户自建资源只支持目录、模块和页面；接口与操作权限由平台授权");
    }
    TenantResource entry = new TenantResource();
    entry.setTenantId(tenant.getId());
    entry.setId(UUID.randomUUID().toString());
    entry.setCode("tenant." + tenant.getId() + "." + entry.getId());
    applyMutation(tenant.getId(), entry, mutation);
    entry.setGrantable(entry.getType() == ResourceType.PAGE);
    em.persist(entry);
    em.flush();
    tenants.increaseAuthorizationVersion(List.of(tenant.getId()));
    return entry;
  }

  @Override
  @Transactional
  public TenantResource update(String id, Mutation mutation) {
    Tenant tenant = lockedManagerTenant();
    validate(mutation);
    TenantResource entry = find(tenant.getId(), id);
    if (entry == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
    }
    if (entry.isPlatformSource() && mutation.type() != entry.getType()) {
      throw badRequest("平台来源资源的类型不能修改");
    }
    if (entry.isManaged()) {
      throw badRequest("服务托管资源只能由所属服务修改");
    }
    if (!entry.isPlatformSource()
        && (mutation.type() == ResourceType.ACTION
            || mutation.type() == ResourceType.API
            || mutation.type() == ResourceType.FEATURE)) {
      throw badRequest("租户自建资源只支持目录、模块和页面");
    }
    applyMutation(tenant.getId(), entry, mutation);
    entry.setLocalModified(true);
    entry.setUpdatedAt(Instant.now());
    em.flush();
    tenants.increaseAuthorizationVersion(List.of(tenant.getId()));
    return entry;
  }

  @Override
  @Transactional
  public void delete(String id) {
    Tenant tenant = lockedManagerTenant();
    TenantResource entry = find(tenant.getId(), id);
    if (entry == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在");
    }
    if (entry.isPlatformSource()) {
      throw badRequest("平台来源资源不可删除，可停用或由平台撤销授权");
    }
    if (entry.isManaged()) {
      throw badRequest("服务托管资源只能由所属服务停用");
    }
    if (!em.createQuery(
            "select r.id from TenantResource r where r.tenantId = :tenant and r.parentId = :parent",
            String.class)
        .setParameter("tenant", tenant.getId())
        .setParameter("parent", id)
        .setMaxResults(1)
        .getResultList()
        .isEmpty()) {
      throw badRequest("请先移动或删除下级资源");
    }
    em.createQuery(
            "update RoleResourceGrant g set g.deletedAt = :deletedAt"
                + " where g.tenantId = :tenant and g.resourceCode = :code and g.deletedAt is null")
        .setParameter("deletedAt", Instant.now())
        .setParameter("tenant", tenant.getId())
        .setParameter("code", entry.getCode())
        .executeUpdate();
    em.remove(entry);
    em.flush();
    tenants.increaseAuthorizationVersion(List.of(tenant.getId()));
  }

  private void applyMutation(String tenantId, TenantResource entry, Mutation mutation) {
    String parentId = blankToNull(mutation.parentId());
    if (Objects.equals(parentId, entry.getId())) {
      throw badRequest("资源不能作为自身的上级");
    }
    TenantResource parentResource = parentId == null ? null : find(tenantId, parentId);
    if (parentId != null
        && (parentResource == null
            || !parentResource.isEnabled()
            || parentResource.getType() == ResourceType.ACTION
            || parentResource.getType() == ResourceType.API
            || (isNavigationType(mutation.type())
                && !isNavigationType(parentResource.getType())))) {
      throw badRequest("上级资源不存在、已停用或不支持下级资源");
    }
    Set<String> visited = new HashSet<>();
    String cursor = parentId;
    while (cursor != null && visited.add(cursor)) {
      if (Objects.equals(cursor, entry.getId())) {
        throw badRequest("资源层级不能形成循环");
      }
      TenantResource parent = find(tenantId, cursor);
      cursor = parent == null ? null : parent.getParentId();
    }
    if (cursor != null) {
      throw badRequest("上级资源层级存在循环");
    }
    entry.setParentId(parentId);
    if (mutation.nameLocalization() != null) {
      var localized = org.simplepoint.api.locale.LocalizedTexts.validate(mutation.nameLocalization(), 100, 32);
      boolean neutralLegacyValue = entry.getNameLocalization() == null
          && localized.translations().isEmpty() && Objects.equals(entry.getName(), localized.defaultValue());
      if (!neutralLegacyValue && !Objects.equals(entry.getNameLocalization(), localized)) {
        org.simplepoint.core.jackson.FieldPermissionPolicy.requireWritable(TenantResource.class, "name");
      }
      if (!neutralLegacyValue) entry.setNameLocalization(localized);
      entry.setName(localized.defaultValue());
    } else {
      if (!Objects.equals(entry.getName(), mutation.name().trim())) {
        org.simplepoint.core.jackson.FieldPermissionPolicy.requireWritable(TenantResource.class, "name");
        String locale = org.simplepoint.core.locale.I18nContextHolder.getLocale().toLanguageTag();
        var translations = new java.util.LinkedHashMap<String, String>(entry.getNameLocalization() == null
            ? Map.of() : entry.getNameLocalization().translations());
        translations.put(org.simplepoint.api.locale.LocalizedTexts.locale(locale), mutation.name().trim());
        entry.setNameLocalization(org.simplepoint.api.locale.LocalizedTexts.validate(
            new org.simplepoint.api.locale.LocalizedText(mutation.name().trim(), locale, translations), 100, 32));
      }
      entry.setName(mutation.name().trim());
    }
    entry.setType(mutation.type());
    entry.setIcon(blankToNull(mutation.icon()));
    entry.setSort(mutation.sort());
    entry.setDescription(blankToNull(mutation.description()));
    if (entry.isPlatformSource()) {
      // Platform route/component remain authoritative; only presentation can change.
      if (Boolean.TRUE.equals(mutation.enabled())
          && (!entry.isSourceAvailable() || entry.isFormallyOffline())) {
        throw badRequest("平台已撤销该资源，租户不能重新启用");
      }
      if (mutation.enabled() != null) {
        entry.setManualDisabled(!mutation.enabled());
        entry.setEnabled(mutation.enabled());
      }
      return;
    }
    if (entry.getType() == ResourceType.PAGE) {
      TenantResource target = find(tenantId, blankToNull(mutation.targetResourceId()));
      if (target == null
          || !target.isEnabled()
          || !target.isPlatformSource()
          || target.getType() != ResourceType.PAGE
          || !target.isGrantable()
          || "hidden".equalsIgnoreCase(target.getRouteKind())
          || !grantableCodes(tenantId, Set.of(target.getCode())).contains(target.getCode())) {
        throw badRequest("自建页面必须绑定当前租户已获授权的平台页面");
      }
      String path = blankToNull(mutation.path());
      if (path == null || path.length() > 200 || !path.matches("^/(?!/)[A-Za-z0-9/_-]+$")) {
        throw badRequest("页面路径必须以 / 开头，只能包含字母、数字、斜杠、下划线和连字符");
      }
      if (!em.createQuery(
              "select r.id from TenantResource r where r.tenantId = :tenant and r.enabled = true"
                  + " and r.path = :path and r.id <> :id",
              String.class)
          .setParameter("tenant", tenantId)
          .setParameter("path", path)
          .setParameter("id", entry.getId())
          .setMaxResults(1)
          .getResultList()
          .isEmpty()) {
        throw badRequest("页面路径已存在");
      }
      entry.setTargetResourceId(target.getId());
      entry.setPath(path);
      entry.setComponent(target.getComponent());
      entry.setRouteKind(target.getRouteKind());
      entry.setGrantable(true);
    } else {
      entry.setTargetResourceId(null);
      entry.setPath(null);
      entry.setComponent(null);
      entry.setRouteKind(null);
      entry.setGrantable(false);
    }
    if (mutation.enabled() != null) {
      entry.setManualDisabled(!mutation.enabled());
      entry.setEnabled(mutation.enabled());
    }
  }

  private static void validate(Mutation mutation) {
    if (mutation == null
        || mutation.name() == null
        || mutation.name().isBlank()
        || mutation.name().codePointCount(0, mutation.name().length()) > 100
        || mutation.type() == null
        || mutation.icon() != null && mutation.icon().length() > 100
        || mutation.description() != null && mutation.description().length() > 255) {
      throw badRequest("资源名称、类型或长度无效");
    }
    if (mutation.nameLocalization() != null) {
      try {
        var value = org.simplepoint.api.locale.LocalizedTexts.validate(mutation.nameLocalization(), 100, 32);
        if (!value.defaultValue().equals(mutation.name())) throw new IllegalArgumentException();
      } catch (IllegalArgumentException error) {
        throw new org.simplepoint.plugin.rbac.tenant.api.error.LocalizedResourceNameException();
      }
    }
  }

  private static boolean isNavigationType(ResourceType type) {
    return type == ResourceType.GROUP || type == ResourceType.MODULE || type == ResourceType.PAGE;
  }

  @Override
  public List<TenantResource> activeResources(String tenantId) {
    return new TenantResourceReadQuery(em, tenantId).activeResources();
  }

  private String currentActorId() {
    var context = org.simplepoint.core.AuthorizationContextHolder.getContext();
    return context == null ? null : context.getUserId();
  }

  @Override
  public Set<String> roleResourceIds(String tenantId) {
    return new TenantResourceReadQuery(em, tenantId).roleResourceIds();
  }

  @Override
  public AuthorizationSelection authorization(
      String tenantId, Collection<String> roleCodes, boolean allEntitled, String actorId) {
    return new TenantResourceReadQuery(em, tenantId).authorization(roleCodes, allEntitled, actorId);
  }

  @Override
  public List<TenantResource> navigationResources(String tenantId, Collection<String> heldCodes) {
    return new TenantResourceReadQuery(em, tenantId)
        .navigationResources(heldCodes, currentActorId());
  }

  @Override
  public List<TenantResource> selectedResources(String tenantId, Collection<String> codes) {
    return new TenantResourceReadQuery(em, tenantId).selectedResources(codes, currentActorId());
  }

  @Override
  public Set<String> grantableCodes(String tenantId, Collection<String> requested) {
    return new TenantResourceReadQuery(em, tenantId).authorizationCodes(requested, false);
  }

  @Override
  public Set<String> eligiblePermissionCodes(String tenantId, Collection<String> requested) {
    return new TenantResourceReadQuery(em, tenantId).eligiblePermissionCodes(requested);
  }

  @Override
  public Set<String> effectivePlatformCodes(String tenantId, Collection<String> requested) {
    return new TenantResourceReadQuery(em, tenantId).authorizationCodes(requested, true);
  }

  @Override
  public Set<String> managedGrantableCodes(String tenantId, Collection<String> requested) {
    return new TenantResourceReadQuery(em, tenantId).managedGrantableCodes(requested);
  }

  @Override
  public Set<String> activeManagedGrantableCodes(String tenantId) {
    return new TenantResourceReadQuery(em, tenantId).activeManagedGrantableCodes();
  }

  @Override
  public List<TenantResource> managedNavigationResources(
      String tenantId, Collection<String> heldCodes) {
    return new TenantResourceReadQuery(em, tenantId).managedNavigationResources(heldCodes);
  }

  private TenantResource find(String tenantId, String id) {
    return id == null ? null : em.find(TenantResource.class, new TenantResourceKey(tenantId, id));
  }

  private Tenant managerTenant() {
    return ownerTenant(false);
  }

  private Tenant lockedManagerTenant() {
    return ownerTenant(true);
  }

  private Tenant ownerTenant(boolean lock) {
    String id =
        new TenantOwnerBoundary(em, () -> new AccessDeniedException("Tenant owner required"))
            .currentTenant(lock);
    var ready =
        em.createQuery(
                "select s.state from TenantResourceSetup s where s.tenantId = :tenant",
                TenantResourceSetup.State.class)
            .setParameter("tenant", id)
            .getResultList();
    if (ready.isEmpty() || ready.getFirst() != TenantResourceSetup.State.READY) {
      throw new TenantResourceSetupException(TenantResourceSetupException.Code.CATALOG_NOT_READY);
    }
    return em.find(Tenant.class, id);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static ResponseStatusException badRequest(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }
}
