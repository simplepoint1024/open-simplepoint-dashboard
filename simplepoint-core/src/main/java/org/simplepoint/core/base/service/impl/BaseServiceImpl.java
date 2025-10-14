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
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.Lock;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.api.base.BaseEntity;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.api.base.audit.ModifyDataAuditingService;
import org.simplepoint.api.lock.DistributedLocking;
import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.api.security.generator.JsonSchemaGenerator;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.api.security.service.JsonSchemaDetailsService;
import org.simplepoint.api.security.simple.SimpleFieldPermissions;
import org.simplepoint.core.context.UserContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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

  private final R repository;

  private final UserContext<BaseUser> userContext;

  private final DetailsProviderService detailsProviderService;

  private final DistributedLocking<Lock> locking;

  /**
   * Configurable function to generate form schemas for entities.
   * If not provided, schema generation will not be available.
   *
   * @param repository             the repository to be used for entity operations
   * @param userContext            the user context for accessing user information
   * @param detailsProviderService the access control service for managing permissions
   * @param locking                the distributed locking mechanism
   */
  public BaseServiceImpl(
      final R repository,
      final UserContext<BaseUser> userContext,
      final DetailsProviderService detailsProviderService,
      final DistributedLocking<Lock> locking
  ) {
    this.repository = repository;
    this.userContext = userContext;
    this.detailsProviderService = detailsProviderService;
    this.locking = locking;
  }

  /**
   * Constructs a BaseServiceImpl with the specified repository and access metadata sync service.
   *
   * @param repository the repository to be used for entity operations
   * @param locking    the distributed locking mechanism
   */
  public BaseServiceImpl(
      final R repository,
      final UserContext<BaseUser> userContext,
      final DistributedLocking<Lock> locking
  ) {
    this(repository, userContext, null, locking);
  }

  /**
   * Constructs a BaseServiceImpl with the specified repository and form schema generator.
   *
   * @param repository             the repository to be used for entity operations
   * @param userContext            the user context for accessing user information
   * @param detailsProviderService the access control service for managing permissions
   */
  public BaseServiceImpl(
      final R repository,
      final UserContext<BaseUser> userContext,
      final DetailsProviderService detailsProviderService
  ) {
    this(repository, userContext, detailsProviderService, null);
  }

  /**
   * Constructs a BaseServiceImpl with the specified repository.
   *
   * @param repository the repository to be used for entity operations
   */
  public BaseServiceImpl(
      final R repository
  ) {
    this(repository, null, null, null);
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
  public ObjectNode schema() {
    if (detailsProviderService == null) {
      throw new IllegalStateException("DetailsProviderService is null");
    }

    var formSchemaGenerator = detailsProviderService.getDialect(JsonSchemaDetailsService.class);
    if (formSchemaGenerator == null) {
      throw new RuntimeException("Form Schema Generator has not been initialized");
    }
    Class<T> domainClass = repository.getDomainClass();

    var jsonSchemaGenerate = detailsProviderService.getDialect(JsonSchemaGenerator.class);
    ObjectNode schema = jsonSchemaGenerate.generateSchema(domainClass);
    BaseUser details = userContext.getDetails();
    // If user is super admin, return full schema
    if (details.superAdmin()) {
      return schema;
    }
    Set<SimpleFieldPermissions> fields = formSchemaGenerator.loadCurrentUserSchemaPropertiesPermissions(details, domainClass.getName());
    Set<String> fieldNames = new HashSet<>();
    var propsNode = schema.get("properties");
    if (propsNode instanceof ObjectNode props) {
      for (SimpleFieldPermissions field : fields) {
        String fieldName = field.getFieldName();
        // 处理只读属性
        if (Objects.nonNull(fieldName) && props.get(fieldName) instanceof ObjectNode prop) {
          String action = field.action();
          prop.put("readOnly", action == null || !action.contains("WRITE"));
          fieldNames.add(fieldName);
        }
      }
    }
    schema.withObjectProperty("properties").retain(fieldNames);
    return schema;
  }

  /**
   * Adds a new entity to the repository.
   *
   * @param entity the entity to add
   * @param <S>    the type of the entity
   * @return the added entity
   * @throws Exception if an error occurs during the addition
   */
  @Override
  public <S extends T> S add(S entity) throws Exception {
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
  public List<T> add(Collection<T> entities) {
    List<T> save = repository.saveAll(entities);
    getModifyDataAuditingServices().forEach(service -> service.save(save, repository.getDomainClass()));
    return save;
  }

  /**
   * Modifies an entity by its ID.
   *
   * @param entity the entity to modify
   * @param <S>    the type of the entity
   * @return the modified entity
   * @throws Exception if an error occurs during the modification
   */
  @Override
  public <S extends T> T modifyById(S entity) throws Exception {
    if (entity == null) {
      throw new NullPointerException("entity is null");
    }
    if (entity.getId() == null) {
      throw new NullPointerException("entity id is null");
    }
    detailsProviderService.getDialect(ModifyDataAuditingService.class);
    repository.findById(entity.getId()).ifPresent(t -> {
      BeanUtil.copyProperties(t, entity, getCopyOptions());
      getModifyDataAuditingServices().forEach(service -> service.modify(t, entity, repository.getDomainClass()));
    });
    return lock(getClass().getName() + ".modifyById", () -> repository.updateById(entity), 60, 60);
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
    repository.deleteAll();
  }

  /**
   * Removes an entity by its ID.
   *
   * @param id the ID of the entity to remove
   */
  @Override
  public void removeById(I id) {
    findById(id).ifPresent(entity -> getModifyDataAuditingServices().forEach(service -> service.delete(Set.of(entity), repository.getDomainClass())));
    repository.deleteById(id);
  }

  /**
   * Removes entities by their IDs.
   *
   * @param ids the collection of IDs of the entities to remove
   */
  @Override
  public void removeByIds(Collection<I> ids) {
    List<T> deleteData = findAllByIds(ids);
    if (!deleteData.isEmpty()) {
      getModifyDataAuditingServices().forEach(service -> service.delete(deleteData, repository.getDomainClass()));
    }
    repository.deleteByIds(ids);
  }

  /**
   * Finds an entity by its ID.
   *
   * @param id the ID of the entity to find
   * @return an Optional containing the entity if found
   */
  @Override
  public Optional<T> findById(I id) {
    return repository.findById(id);
  }

  /**
   * Finds all entities by their IDs.
   *
   * @param ids the iterable collection of IDs of the entities to find
   * @return the list of found entities
   */
  @Override
  public List<T> findAllByIds(Iterable<I> ids) {
    return repository.findAllByIds(ids);
  }

  /**
   * Finds all entities based on attributes.
   *
   * @param attributes the map of attributes to filter entities
   * @return the list of matching entities
   */
  @Override
  public List<T> findAll(Map<String, String> attributes) {
    return repository.findAll(attributes);
  }

  /**
   * Retrieves a paginated list of entities based on attributes and pageable parameters.
   *
   * @param attributes the map of attributes to filter entities
   * @param pageable   the pagination and sorting information
   * @param <S>        the type of the entity
   * @return the paginated list of entities
   * @throws Exception if an error occurs during retrieval
   */
  @Override
  public <S extends T> Page<S> limit(Map<String, String> attributes, Pageable pageable)
      throws Exception {
    attributes.remove("number");
    attributes.remove("size");
    Page<S> limit = repository.limit(attributes, pageable);
    this.validate(limit.getContent());
    return limit;
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
    JsonSchemaDetailsService dialect = detailsProviderService.getDialect(JsonSchemaDetailsService.class);
    BaseUser details = userContext.getDetails();
    if (details.superAdmin()) {
      return;
    }
    // 验证字段权限 ，如果没有改字段权限，则设置为 null
    Set<SimpleFieldPermissions> allowField =
        dialect.loadCurrentUserSchemaPropertiesPermissions(details, repository.getDomainClass().getName());
    List<String> fieldNames = allowField.stream().map(SimpleFieldPermissions::getFieldName).filter(Objects::nonNull).toList();
    for (S item : data) {
      Field[] fields = item.getClass().getDeclaredFields();
      for (Field field : fields) {
        if (!fieldNames.contains(field.getName())) {
          if (!Modifier.isFinal(field.getModifiers())) {
            field.setAccessible(true);
            try {
              field.set(item, null);
            } catch (IllegalAccessException e) {
              log.error("Failed to set field to null: {}", field.getName(), e);
            }
          }
        }
      }
    }
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
    return repository.exists(example);
  }

  /**
   * Checks if an entity exists by its ID.
   *
   * @param id the ID of the entity
   * @return true if the entity exists, false otherwise
   */
  @Override
  public boolean existsById(I id) {
    return repository.existsById(id);
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
    return repository.count(example);
  }

  /**
   * Executes a callable with distributed locking.
   *
   * @param key       the locking key
   * @param runnable  the callable to execute
   * @param waitTime  the wait time for acquiring the lock
   * @param leaseTime the lease time for holding the lock
   * @param <V>       the type of the result
   * @return the result of the callable
   * @throws Exception if an error occurs during execution
   */
  public <V> V lock(String key, Callable<V> runnable, long waitTime, long leaseTime)
      throws Exception {
    if (locking == null) {
      log.warn(
          "The distributed lock did not take effect, "
              + "check whether the lock implementation "
              + "was introduced and whether the bean was injected!");
      return runnable.call();
    }
    return locking.executeWithLock(key, runnable, waitTime, leaseTime);
  }
}
