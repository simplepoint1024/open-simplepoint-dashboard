/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.core.base.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.api.base.BaseEntity;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.api.base.TenantBaseEntity;
import org.simplepoint.api.base.audit.ModifyDataAuditingService;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.ResourceDataScope;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.core.datascopeannotation.DataScopeContext;
import org.simplepoint.core.datascopeannotation.DataScopeFilter;
import org.simplepoint.core.schema.AnnotatedTableSchemaProvider;
import org.simplepoint.core.base.DecimalColumnPolicy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

/**
 * Abstract service implementation class for managing entities.
 * This class provides common CRUD operations and additional
 * functionality such as distributed locking.
 *
 * @param <R> the repository type for the entity
 * @param <T> the type of the entity
 * @param <I> the type of the entity ID, must be serializable
 */
@Getter
@Slf4j
public class BaseServiceImpl
    <R extends BaseRepository<T, I>, T extends BaseEntity<I>, I extends Serializable>
    implements BaseService<T, I> {

  private final ObjectMapper mapper = new ObjectMapper();

  private static final String DATA_SCOPE_ALL = "ALL";

  private static final String DATA_SCOPE_SELF = "SELF";

  private static final String DATA_SCOPE_DEPT = "DEPT";

  private static final String DATA_SCOPE_DEPT_AND_BELOW = "DEPT_AND_BELOW";

  private static final String DATA_SCOPE_CUSTOM = "CUSTOM";

  private static final String DEFAULT_DATA_SCOPE_OWNER_FIELD = "createdBy";

  private static final String DEFAULT_DATA_SCOPE_DEPT_FIELD = "createOrgDeptId";

  private static final String ORG_DEPT_ID_ATTRIBUTE = "X-Org-Dept-Id";

  private final R repository;

  private final DetailsProviderService detailsProviderService;

  private final AnnotatedTableSchemaProvider tableSchemaProvider;


  /**
   * Constructs a new BaseServiceImpl instance with the specified repository,
   * authorization context holder, and details provider service.
   *
   * @param repository             the repository for managing entities
   * @param detailsProviderService the service for providing details such as JSON schema generation
   */
  public BaseServiceImpl(
      final R repository,
      final DetailsProviderService detailsProviderService
  ) {
    this.repository = repository;
    this.detailsProviderService = detailsProviderService;
    this.tableSchemaProvider = new AnnotatedTableSchemaProvider(detailsProviderService);
  }

  /**
   * Returns a collection of ModifyDataAuditingService instances.
   * These services are used for auditing modifications to data.
   *
   * @return a collection of ModifyDataAuditingService instances
   */
  @Override
  public Collection<ModifyDataAuditingService> getModifyDataAuditingServices() {
    try {
      return detailsProviderService.getDialects(ModifyDataAuditingService.class);
    } catch (Exception e) {
      log.warn("No configured ModifyDataAuditingService found, skipping data auditing.");
      return Set.of();
    }
  }

  /**
   * Returns the metadata for the repository's domain class.
   * If the access metadata resolver is configured, it retrieves the metadata;
   * otherwise, it returns an empty metadata instance.
   *
   * @return Metadata instance containing access permissions and related information
   */
  @Override
  public Map<String, Object> schema() {
    Class<T> domainClass = repository.getDomainClass();
    ObjectNode jsonSchema = getJsonSchema(domainClass);
    Map<String, Object> schema = mapper.convertValue(jsonSchema, new TypeReference<>() {
    });
    Set<Map<String, Object>> buttonDeclarationsSchema = getButtonDeclarationsSchema(domainClass);
    return Map.of(
        "schema", schema,
        "buttons", buttonDeclarationsSchema
    );
  }

  /**
   * Generates a JSON schema for the given domain class,
   * applying field-level permissions based on the current user's details.
   *
   * @param domainClass the domain class for which to generate the schema
   * @return the generated JSON schema with applied field permissions
   */
  protected ObjectNode getJsonSchema(Class<T> domainClass) {
    return tableSchemaProvider.jsonSchema(domainClass);
  }

  /**
   * Retrieves button declarations from the specified domain class.
   *
   * @param domainClass the domain class from which to retrieve button declarations
   * @return a set of maps representing button declaration attributes
   */
  protected Set<Map<String, Object>> getButtonDeclarationsSchema(Class<T> domainClass) {
    return tableSchemaProvider.buttonSchemas(domainClass, getAuthorizationContext());
  }

  /**
   * Extracts the attributes of the given annotation into a map.
   *
   * @param annotation the annotation from which to extract attributes
   * @return a map containing the annotation's attribute names and their corresponding values
   */
  protected Map<String, Object> extractAnnotationAttributes(Annotation annotation) {
    return tableSchemaProvider.annotationAttributes(annotation);
  }

  /**
   * Retrieves all field names from the JSON schema of the specified domain class.
   *
   * @param domainClass the domain class for which to retrieve field names
   * @return a set of all field names in the domain class
   */
  protected Set<String> getAllFieldNames(Class<T> domainClass) {
    ObjectNode jsonSchema = getJsonSchema(domainClass);
    Set<String> fieldNames = new HashSet<>();
    var propsNode = jsonSchema.get("properties");
    if (propsNode instanceof ObjectNode props) {
      props.fieldNames().forEachRemaining(fieldNames::add);
    }
    return fieldNames;
  }

  /**
   * Adds a new entity to the repository.
   *
   * @param entity the entity to add
   * @param <S>    the type of the entity
   * @return the added entity
   */
  @Override
  public <S extends T> S create(S entity) {
    clearNonEditableFieldsForCreate(entity);
    applyCurrentTenantIdIfNecessary(entity);
    applyCreationAuditContext(entity);
    assertCreateAccessible(entity);
    DecimalColumnPolicy.validate(entity);
    S save = repository.save(entity);
    getModifyDataAuditingServices().forEach(service -> service.save(Set.of(save), repository.getDomainClass()));
    return save;
  }

  /**
   * Adds a collection of entities to the repository.
   *
   * @param entities the collection of entities to add
   * @return the list of added entities
   */
  @Override
  public List<T> create(Collection<T> entities) {
    if (entities != null) {
      entities.forEach(e -> {
        clearNonEditableFieldsForCreate(e);
        applyCurrentTenantIdIfNecessary(e);
        applyCreationAuditContext(e);
        assertCreateAccessible(e);
        DecimalColumnPolicy.validate(e);
      });
    }
    List<T> save = repository.saveAll(entities);
    getModifyDataAuditingServices().forEach(service -> service.save(save, repository.getDomainClass()));
    return save;
  }

  @Override
  public void flush() {
    repository.flush();
  }

  /**
   * Applies the current tenant ID to a tenant-aware entity when it is missing.
   *
   * @param entity the entity being created or updated
   */
  protected void applyCurrentTenantIdIfNecessary(BaseEntity<?> entity) {
    if (!(entity instanceof TenantBaseEntity<?> tenantEntity)) {
      return;
    }
    String currentTenantId = currentTenantId();
    if (currentTenantId != null) {
      tenantEntity.setTenantId(currentTenantId);
      return;
    }
    if (tenantEntity.getTenantId() == null || tenantEntity.getTenantId().isBlank()) {
      throw new IllegalStateException("Tenant-aware entity requires an active tenant context");
    }
  }

  /**
   * Resolves the current tenant ID from the active authorization context.
   *
   * @return the trimmed tenant ID, or {@code null} when no tenant is available
   */
  protected String currentTenantId() {
    AuthorizationContext authorizationContext = getAuthorizationContext();
    if (authorizationContext == null) {
      return null;
    }
    String tenantId = authorizationContext.getAttribute("X-Tenant-Id");
    if (tenantId == null) {
      return null;
    }
    String trimmed = tenantId.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /**
   * Replaces client-supplied audit ownership with the authenticated actor before insert.
   * This makes newly-created business rows immediately compatible with SELF and department
   * data scopes and prevents callers from forging row ownership.
   *
   * @param entity entity being created
   */
  protected void applyCreationAuditContext(BaseEntity<?> entity) {
    AuthorizationContext context = getAuthorizationContext();
    if (entity == null || context == null) {
      return;
    }
    String userId = trimToNull(context.getUserId());
    entity.setCreatedBy(userId);
    entity.setUpdatedBy(userId);
    String orgDeptId = trimToNull(context.getAttribute(ORG_DEPT_ID_ATTRIBUTE));
    entity.setCreateOrgDeptId(orgDeptId);
  }

  /**
   * Records the authenticated actor as the updater while retaining the original ownership.
   *
   * @param entity entity being modified
   */
  protected void applyModificationAuditContext(BaseEntity<?> entity) {
    AuthorizationContext context = getAuthorizationContext();
    if (entity == null || context == null) {
      return;
    }
    String userId = trimToNull(context.getUserId());
    entity.setUpdatedBy(userId);
  }

  protected void assertCreateAccessible(T entity) {
    if (isDataScopeRestricted()) {
      assertAccessible(entity);
    }
  }

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /**
   * Modifies an entity by its ID.
   *
   * @param entity the entity to modify
   * @param <S>    the type of the entity
   * @return the modified entity
   */
  @Override
  public <S extends T> T modifyById(S entity) {
    if (entity == null) {
      throw new NullPointerException("entity is null");
    }
    if (entity.getId() == null) {
      throw new NullPointerException("entity id is null");
    }
    repository.enableTenantFilter();
    Optional<T> existing = repository.findById(entity.getId()).filter(this::isActive);
    if (existing.isEmpty()) {
      throw new NoSuchElementException("entity not found: " + entity.getId());
    }
    T db = existing.get();
    assertAccessible(db);
    {
      // Only these fields are allowed to be modified by the client
      Set<String> scopeFields = getAllFieldNames(getRepository().getDomainClass());
      // Remove fields that the current user cannot write (non-EDITABLE)
      scopeFields.removeAll(getNonWritableFieldNames(entity.getClass()));

      // For fields NOT in scopeFields, copy from db -> entity (including nulls)
      CopyOptions options = CopyOptions.create()
          .setIgnoreNullValue(false) // must overwrite with nulls from db to revert forbidden changes
          .setIgnoreError(true)
          .setFieldNameEditor(name -> scopeFields.contains(name) ? null : name); // null => skip copy for allowed fields

      BeanUtil.copyProperties(db, entity, options);
      applyModificationAuditContext(entity);
      assertAccessible(entity);
      DecimalColumnPolicy.validate(entity);

      // Audit diff between db (before) and entity (after merge)
      getModifyDataAuditingServices().forEach(svc -> svc.modify(db, entity, repository.getDomainClass()));
    }

    return repository.updateById(entity);
  }

  /**
   * Returns the copy options for property copying.
   * The default implementation ignores null values during copying.
   *
   * @return the copy options
   */
  @Override
  public CopyOptions getCopyOptions() {
    return CopyOptions.create().setIgnoreNullValue(true);
  }

  /**
   * Removes all entities from the repository.
   */
  @Override
  public void removeAll() {
    if (isDataScopeRestricted()) {
      throw new AccessDeniedException("Deleting all rows requires an explicit ALL data scope");
    }
    repository.enableTenantFilter();
    repository.deleteAll();
  }

  /**
   * Removes an entity by its ID.
   *
   * @param id the ID of the entity to remove
   */
  @Override
  public void removeById(I id) {
    repository.enableTenantFilter();
    Optional<T> entity = repository.findById(id).filter(this::isActive);
    if (entity.isEmpty()) {
      return;
    }
    T data = entity.get();
    assertAccessible(data);
    getModifyDataAuditingServices().forEach(service -> service.delete(Set.of(data), repository.getDomainClass()));
    repository.deleteById(id);
  }

  /**
   * Removes entities by their IDs.
   *
   * @param ids the collection of IDs of the entities to remove
   */
  @Override
  public void removeByIds(Collection<I> ids) {
    repository.enableTenantFilter();
    List<T> deleteData = repository.findAllByIds(ids).stream().filter(this::isActive).toList();
    boolean restricted = isDataScopeRestricted();
    if (restricted) {
      deleteData.forEach(this::assertAccessible);
    }
    if (!deleteData.isEmpty()) {
      getModifyDataAuditingServices().forEach(service -> service.delete(deleteData, repository.getDomainClass()));
    }
    if (!deleteData.isEmpty()) {
      repository.deleteByIds(deleteData.stream().map(BaseEntity::getId).toList());
    }
  }

  /**
   * Finds an entity by its ID.
   *
   * @param id the ID of the entity to find
   * @return an Optional containing the entity if found
   */
  @Override
  public Optional<T> findById(I id) {
    repository.enableTenantFilter();
    return repository.findById(id).filter(this::isActive).filter(this::isAccessible);
  }

  /**
   * Finds all entities by their IDs.
   *
   * @param ids the iterable collection of IDs of the entities to find
   * @return the list of found entities
   */
  @Override
  public List<T> findAllByIds(Iterable<I> ids) {
    repository.enableTenantFilter();
    return repository.findAllByIds(ids).stream()
        .filter(this::isActive)
        .filter(this::isAccessible)
        .toList();
  }

  /**
   * Finds all entities based on attributes.
   *
   * @param attributes the map of attributes to filter entities
   * @return the list of matching entities
   */
  @Override
  public List<T> findAll(Map<String, String> attributes) {
    return limit(attributes, Pageable.unpaged()).getContent();
  }

  /**
   * Retrieves a paginated list of entities based on attributes and pageable parameters.
   *
   * @param attributes the map of attributes to filter entities
   * @param pageable   the pagination and sorting information
   * @param <S>        the type of the entity
   * @return the paginated list of entities
   */
  @Override
  @DataScopeFilter(ownerField = DEFAULT_DATA_SCOPE_OWNER_FIELD, deptField = DEFAULT_DATA_SCOPE_DEPT_FIELD)
  public <S extends T> Page<S> limit(Map<String, String> attributes, Pageable pageable) {
    Map<String, String> safeAttributes = new LinkedHashMap<>();
    if (attributes != null) {
      safeAttributes.putAll(attributes);
    }
    safeAttributes.put("deletedAt", "is:null");
    validatePublicFilters(safeAttributes);
    Page<S> limit = runWithDefaultDataScope(() -> repository.limit(safeAttributes, pageable));
    this.validate(limit.getContent());
    return limit;
  }

  /**
   * Adds mandatory server scope outside the user's query tree without exposing hidden scope fields.
   * Only subclasses constructing restrictions from authenticated context may use this method.
   *
   * @param attributes user-controlled filters
   * @param required server-owned scalar conditions
   * @param pageable pagination and ordering
   * @param <S> entity type
   * @return scoped records and count
   */
  protected <S extends T> Page<S> limitScoped(
      Map<String, String> attributes, Map<String, String> required, Pageable pageable) {
    Map<String, String> filters = new LinkedHashMap<>();
    if (attributes != null) {
      filters.putAll(attributes);
    }
    // Preserve the public soft-delete behavior; a caller cannot request deleted records.
    filters.remove("deletedAt");
    validatePublicFilters(filters);
    Map<String, String> mandatory = new LinkedHashMap<>(required);
    mandatory.put("deletedAt", "is:null");
    Page<S> result = runWithDefaultDataScope(() -> repository.limitScoped(filters, mandatory, pageable));
    validate(result.getContent());
    return result;
  }

  /**
   * Applies an authorization allow-list without overwriting a same-named public filter.
   *
   * @param attributes public conditions
   * @param field server-selected string member
   * @param allowedValues literal identifiers from the authorization service
   * @param pageable pagination and sort
   * @param <S> entity type
   * @return authorized results and count
   */
  protected <S extends T> Page<S> limitInScope(
      Map<String, String> attributes, String field, Collection<String> allowedValues, Pageable pageable) {
    Map<String, String> filters = new LinkedHashMap<>();
    if (attributes != null) {
      filters.putAll(attributes);
    }
    filters.put("deletedAt", "is:null");
    validatePublicFilters(filters);
    Page<S> result = runWithDefaultDataScope(() -> repository.limitInScope(filters, field, allowedValues, pageable));
    validate(result.getContent());
    return result;
  }

  /**
   * Intersects authorized candidates with an assignment set before paging/counting.
   *
   * @param attributes public filters
   * @param memberships server-owned membership predicates
   * @param pageable pagination and sort
   * @param <S> entity type
   * @return visible matching rows and count
   */
  protected <S extends T> Page<S> limitMemberships(
      Map<String, String> attributes, List<org.simplepoint.api.base.QueryMembership> memberships, Pageable pageable) {
    Map<String, String> filters = new LinkedHashMap<>();
    if (attributes != null) {
      filters.putAll(attributes);
    }
    filters.put("deletedAt", "is:null");
    validatePublicFilters(filters);
    Page<S> result = runWithDefaultDataScope(() -> repository.limitMemberships(filters, memberships, pageable));
    validate(result.getContent());
    return result;
  }

  /** Adds server-owned EXISTS restrictions while retaining the ordinary data-scope policy. */
  protected <S extends T> Page<S> limitReferencedScoped(
      Map<String, String> attributes, Map<String, String> required,
      List<org.simplepoint.api.base.QueryReferenceMembership> memberships, Pageable pageable) {
    Map<String, String> filters = new LinkedHashMap<>();
    if (attributes != null) filters.putAll(attributes);
    filters.remove("deletedAt");
    validatePublicFilters(filters);
    Map<String, String> mandatory = new LinkedHashMap<>(required);
    mandatory.put("deletedAt", "is:null");
    Page<S> result = runWithDefaultDataScope(() ->
        repository.limitReferencedScoped(filters, mandatory, memberships, pageable));
    validate(result.getContent());
    return result;
  }

  private void validatePublicFilters(Map<String, String> safeAttributes) {
    // Public list adapters opt in explicitly. Legacy services keep their domain-specific semantics.
    if (org.simplepoint.core.query.QueryCapabilities.declaration(repository.getDomainClass())
        != null) {
      safeAttributes.forEach(
          (field, value) -> {
            if (field == null) {
              throw new org.simplepoint.core.query.InvalidQueryException("筛选字段不能为空");
            }
            if (Set.of(
                    "page",
                    "size",
                    "sort",
                    "deletedAt",
                    org.simplepoint.core.query.QueryDocument.PARAMETER)
                .contains(field)) {
              return;
            }
            if (value != null && value.startsWith("{")) {
              return;
            } // The structured compiler checks its own field/operator.
            org.simplepoint.core.query.QueryCapabilities.require(
                repository.getDomainClass(),
                field,
                org.simplepoint.core.query.FilterCondition.parse(value).operator(),
                false);
          });
    }
  }

  /**
   * Validates a collection of entities based on user permissions.
   * Fields that the user does not have permission to access are set to null.
   *
   * @param data the collection of entities to validate
   * @param <S>  the type of the entity
   */
  @Override
  public <S extends T> void validate(Collection<S> data) {
    // Read-path field validation: handled at the serialization layer by FieldScopeJacksonModule.
    // Non-EDITABLE fields are already stripped/masked in the JSON response; no in-memory mutation
    // of JPA-managed entities is performed here to avoid unintended dirty-state flushes.
  }

  /**
   * Checks if an entity exists based on the given example.
   *
   * @param example the example entity
   * @param <S>     the type of the entity
   * @return true if the entity exists, false otherwise
   */
  @Override
  public <S extends T> boolean exists(S example) {
    return count(example) > 0;
  }

  /**
   * Checks if an entity exists by its ID.
   *
   * @param id the ID of the entity
   * @return true if the entity exists, false otherwise
   */
  @Override
  public boolean existsById(I id) {
    return findById(id).isPresent();
  }

  /**
   * Counts the number of entities that match the given example.
   *
   * @param example the example entity
   * @param <S>     the type of the entity
   * @return the count of matching entities
   */
  @Override
  public <S extends T> long count(S example) {
    return runWithDefaultDataScope(() -> repository.count(example));
  }

  /**
   * Retrieves the current authorization context.
   *
   * @return the current AuthorizationContext
   * @throws IllegalStateException if the AuthorizationContextHolder is null
   */
  public AuthorizationContext getAuthorizationContext() {
    return AuthorizationContextHolder.getContext();
  }

  /**
   * Returns the set of field names that the current user cannot write based on their field permissions.
   * Fields with access type other than {@code EDITABLE} (i.e. HIDDEN, MASKED, VISIBLE) are read-only.
   * Used in {@link #modifyById} to preserve DB values for protected fields.
   *
   * @param entityClass the entity class; used to build the {@code "SimpleClassName#fieldName"} lookup key
   * @return names of non-writable fields, or an empty set when no field permissions apply
   */
  protected final Set<String> getNonWritableFieldNames(Class<?> entityClass) {
    AuthorizationContext ctx = getAuthorizationContext();
    if (ctx == null) {
      return Set.of();
    }
    Map<String, String> fieldPerms = ctx.activeFieldPermissions();
    if (fieldPerms == null) fieldPerms = ctx.getFieldPermissions();
    if (fieldPerms == null || fieldPerms.isEmpty()) {
      return Set.of();
    }
    Set<String> result = new HashSet<>();
    for (Class<?> type = entityClass; type != null && type != Object.class; type = type.getSuperclass()) {
      for (Field field : type.getDeclaredFields()) {
        if (!org.simplepoint.core.jackson.FieldPermissionPolicy.writable(ctx, entityClass, field.getName())) {
          result.add(field.getName());
        }
      }
    }
    return result;
  }

  /**
   * Clears fields that the current user cannot write (non-EDITABLE) from an entity before creation.
   * Reference fields are cleared; restricted primitives reject creation because absence cannot be represented.
   * This prevents clients from supplying values for fields they have no write permission for.
   *
   * @param entity the entity being created
   */
  protected final void clearNonEditableFieldsForCreate(Object entity) {
    if (entity == null) {
      return;
    }
    AuthorizationContext ctx = getAuthorizationContext();
    if (ctx == null) {
      return;
    }
    Map<String, String> fieldPerms = ctx.activeFieldPermissions();
    if (fieldPerms == null) fieldPerms = ctx.getFieldPermissions();
    if (fieldPerms == null || fieldPerms.isEmpty()) {
      return;
    }
    Class<?> clazz = entity.getClass();
    while (clazz != null && clazz != Object.class) {
      for (Field field : clazz.getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
          continue;
        }
        if (!org.simplepoint.core.jackson.FieldPermissionPolicy.writable(ctx, entity.getClass(), field.getName())) {
          if (field.getType().isPrimitive()) {
            throw new AccessDeniedException(
                "Non-writable primitive field requires an explicit server-side creation policy: "
                    + field.getName());
          }
          try {
            field.setAccessible(true);
            field.set(entity, null);
          } catch (IllegalAccessException | RuntimeException error) {
            throw new AccessDeniedException(
                "Cannot enforce field permission: " + field.getName(), error);
          }
        }
      }
      clazz = clazz.getSuperclass();
    }
  }

  /** Domain validation must inspect the same ACL-correct values that the generic writer persists. */
  protected final void restoreNonWritableFields(T current, T proposed) {
    Set<String> protectedFields = getNonWritableFieldNames(proposed.getClass());
    if (protectedFields.isEmpty()) return;
    BeanUtil.copyProperties(current, proposed, CopyOptions.create().setIgnoreNullValue(false)
        .setIgnoreError(true).setFieldNameEditor(name -> protectedFields.contains(name) ? name : null));
  }

  private <V> V runWithDefaultDataScope(Supplier<V> supplier) {
    if (!hasApplicableDataScope()) {
      DataScopeCondition previous = DataScopeContext.get();
      DataScopeContext.set(new DataScopeCondition(
          DATA_SCOPE_ALL, dataScopeDeptField(), dataScopeOwnerField(), null, Set.of()
      ));
      try {
        return supplier.get();
      } finally {
        if (previous == null) {
          DataScopeContext.clear();
        } else {
          DataScopeContext.set(previous);
        }
      }
    }
    DataScopeCondition previous = DataScopeContext.get();
    AuthorizationContext context = getAuthorizationContext();
    if (previous != null && useInheritedDataScope() && (context == null || context.activeDataScope() == null)) {
      return supplier.get();
    }
    DataScopeCondition condition = buildDefaultDataScopeCondition();
    if (condition == null && previous == null) {
      return supplier.get();
    }
    // Explicit page policies must use this entity mapping, even under an inherited AOP annotation.
    DataScopeContext.set(condition == null ? new DataScopeCondition(DATA_SCOPE_ALL, dataScopeDeptField(),
        dataScopeOwnerField(), null, Set.of()) : condition);
    try {
      return supplier.get();
    } finally {
      if (previous == null) DataScopeContext.clear();
      else DataScopeContext.set(previous);
    }
  }

  protected boolean useInheritedDataScope() { return true; }

  protected DataScopeCondition buildDefaultDataScopeCondition() {
    AuthorizationContext ctx = getAuthorizationContext();
    if (ctx != null && ctx.activeDataScope() == null && Boolean.TRUE.equals(ctx.getIsAdministrator())) {
      return null;
    }
    ResourceDataScope resourceScope = ctx == null ? null : ctx.activeDataScope();
    String scopeType = resourceScope == null ? (ctx == null ? null : ctx.getDataScopeType())
        : resourceScope.type();
    if (DATA_SCOPE_ALL.equals(scopeType)) {
      return null;
    }
    if (resourceScope != null) return resourceScope.condition(dataScopeDeptField(), dataScopeOwnerField(), ctx);
    return new DataScopeCondition(
        scopeType == null || scopeType.isBlank() ? "DENY" : scopeType,
        dataScopeDeptField(),
        dataScopeOwnerField(),
        ctx == null ? null : ctx.getUserId(),
        resourceScope == null ? (ctx == null ? Set.of() : ctx.getDeptIds())
            : resourceScope.departmentIds(),
        resourceScope == null ? ctx != null && Boolean.TRUE.equals(ctx.getDataScopeIncludeSelf())
            : resourceScope.includeSelf()
    );
  }

  private boolean isDataScopeRestricted() {
    if (!hasApplicableDataScope()) {
      return false;
    }
    AuthorizationContext context = getAuthorizationContext();
    if (context != null && context.activeDataScope() != null) {
      return buildDefaultDataScopeCondition() != null;
    }
    DataScopeCondition condition = DataScopeContext.get();
    if (condition != null) {
      return !condition.isAllData();
    }
    return buildDefaultDataScopeCondition() != null;
  }

  protected final void assertAccessible(T entity) {
    if (!isAccessible(entity)) {
      throw new AccessDeniedException("Data scope denies access to entity: " + entity.getId());
    }
  }

  protected boolean isAccessible(T entity) {
    if (entity == null) {
      return false;
    }
    AuthorizationContext ctx = getAuthorizationContext();
    if (!hasApplicableDataScope() || (ctx != null && ctx.activeDataScope() == null && Boolean.TRUE.equals(ctx.getIsAdministrator()))) {
      return true;
    }
    if (ctx == null) {
      return false;
    }
    ResourceDataScope resourceScope = ctx.activeDataScope();
    String scopeType = resourceScope == null ? ctx.getDataScopeType() : resourceScope.type();
    if (DATA_SCOPE_ALL.equals(scopeType)) {
      return true;
    }
    if (scopeType == null || scopeType.isBlank()) {
      return false;
    }
    if (resourceScope != null) return resourceScope.allows(dataScopeDeptField(), dataScopeOwnerField(), ctx,
        field -> permissionFieldValue(entity, field));
    return switch (scopeType) {
      case DATA_SCOPE_SELF -> {
        Object owner = readFieldValue(entity, dataScopeOwnerField());
        yield owner != null && owner.toString().equals(ctx.getUserId());
      }
      case DATA_SCOPE_DEPT, DATA_SCOPE_DEPT_AND_BELOW, DATA_SCOPE_CUSTOM -> {
        Object dept = readFieldValue(entity, dataScopeDeptField());
        Set<String> deptIds = resourceScope == null ? ctx.getDeptIds() : resourceScope.departmentIds();
        boolean deptAllowed = dept != null && deptIds != null && deptIds.contains(dept.toString());
        boolean selfAllowed = (resourceScope == null ? Boolean.TRUE.equals(ctx.getDataScopeIncludeSelf())
            : resourceScope.includeSelf())
            && isOwnedByCurrentUser(entity, ctx);
        yield deptAllowed || selfAllowed;
      }
      default -> false;
    };
  }

  private boolean isOwnedByCurrentUser(T entity, AuthorizationContext ctx) {
    Object owner = readFieldValue(entity, dataScopeOwnerField());
    return owner != null && owner.toString().equals(ctx.getUserId());
  }

  private boolean isActive(T entity) {
    return entity != null && entity.getDeletedAt() == null && isInCurrentTenant(entity);
  }

  private boolean isInCurrentTenant(T entity) {
    if (!(entity instanceof TenantBaseEntity<?> tenantEntity)) {
      return true;
    }
    String tenantId = currentTenantId();
    AuthorizationContext context = getAuthorizationContext();
    if (tenantId == null && context != null && Boolean.TRUE.equals(context.getIsAdministrator())
        && context.getScopeType() == org.simplepoint.core.AuthorizationScopeType.PLATFORM) {
      return true;
    }
    return tenantId != null && tenantId.equals(tenantEntity.getTenantId());
  }

  /**
   * Returns the owner field used by base data-scope checks.
   * Override together with {@link #dataScopeDeptField()} when an entity does not use the standard names.
   */
  protected String dataScopeOwnerField() {
    var model = repository.getDomainClass();
    var mapping = model == null ? null : model.getAnnotation(org.simplepoint.core.permission.PermissionModel.class);
    return mapping == null ? DEFAULT_DATA_SCOPE_OWNER_FIELD : mapping.owner();
  }

  /**
   * Returns the department field used by base data-scope checks.
   * Override together with {@link #dataScopeOwnerField()} when an entity does not use the standard names.
   */
  protected String dataScopeDeptField() {
    var model = repository.getDomainClass();
    var mapping = model == null ? null : model.getAnnotation(org.simplepoint.core.permission.PermissionModel.class);
    return mapping == null ? DEFAULT_DATA_SCOPE_DEPT_FIELD : mapping.department();
  }

  /**
   * Whether generic row-level data scope should apply to this service.
   * Security metadata and tenant configuration services should return {@code false};
   * those services enforce their own tenant or package boundaries instead of filtering
   * configuration rows by the operator's department or creator.
   *
   * @return true when the default row-level data scope is applicable
   */
  protected boolean isDataScopeApplicable() {
    return true;
  }

  private boolean hasApplicableDataScope() {
    AuthorizationContext context = getAuthorizationContext();
    return context != null && context.activeDataScope() != null || isDataScopeApplicable();
  }

  protected Object permissionFieldValue(T entity, String name) {
    for (Class<?> type = entity.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
      try {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(entity);
      } catch (NoSuchFieldException ignored) {
        // Search inherited persistent fields.
      } catch (IllegalAccessException error) {
        throw new AccessDeniedException("权限字段不可读取: " + name, error);
      }
    }
    throw new AccessDeniedException("权限字段映射已失效: " + name);
  }

  private Object readFieldValue(Object target, String fieldName) {
    Class<?> clazz = target.getClass();
    while (clazz != null && clazz != Object.class) {
      try {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
      } catch (NoSuchFieldException ignored) {
        clazz = clazz.getSuperclass();
      } catch (IllegalAccessException e) {
        return null;
      }
    }
    return null;
  }
}
