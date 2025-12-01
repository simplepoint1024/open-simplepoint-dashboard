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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.api.base.BaseEntity;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.api.base.audit.ModifyDataAuditingService;
import org.simplepoint.api.security.base.BaseUser;
import org.simplepoint.api.security.generator.JsonSchemaGenerator;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.api.security.service.JsonSchemaDetailsService;
import org.simplepoint.core.annotation.ButtonDeclaration;
import org.simplepoint.core.annotation.ButtonDeclarations;
import org.simplepoint.core.context.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
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

  private final ObjectMapper mapper = new ObjectMapper();
  private final R repository;

  private final UserContext<BaseUser> userContext;

  private final DetailsProviderService detailsProviderService;

  /**
   * Constructs a BaseServiceImpl with the specified repository and form schema generator.
   *
   * @param repository             the repository to be used for entity operations
   * @param userContext            the user context for accessing user information
   * @param detailsProviderService the access control service for managing permissions
   */
  public BaseServiceImpl(
      final R repository,
      @Autowired(required = false) final UserContext<BaseUser> userContext,
      final DetailsProviderService detailsProviderService
  ) {
    this.repository = repository;
    this.userContext = userContext;
    this.detailsProviderService = detailsProviderService;
  }

  /**
   * Constructs a BaseServiceImpl with the specified repository.
   *
   * @param repository the repository to be used for entity operations
   */
  public BaseServiceImpl(
      final R repository
  ) {
    this(repository, null, null);
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
    Map<String, Object> schema = mapper.convertValue(jsonSchema, new TypeReference<Map<String, Object>>() {
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
    if (detailsProviderService == null) {
      throw new IllegalStateException("DetailsProviderService is null");
    }

    var formSchemaGenerator = detailsProviderService.getDialect(JsonSchemaDetailsService.class);
    if (formSchemaGenerator == null) {
      throw new RuntimeException("Form Schema Generator has not been initialized");
    }
    var jsonSchemaGenerate = detailsProviderService.getDialect(JsonSchemaGenerator.class);
    ObjectNode schema = jsonSchemaGenerate.generateSchema(domainClass);

    // 获取 properties 节点
    ObjectNode propertiesNode = (ObjectNode) schema.get("properties");

    // 收集字段和对应的 x-order
    List<Map.Entry<String, JsonNode>> fields = new LinkedList<>();
    propertiesNode.fields().forEachRemaining(fields::add);

    // 按 x-order 排序
    fields.sort(Comparator.comparingInt(entry -> {
      JsonNode orderNode = entry.getValue().get("x-order");
      return orderNode != null ? orderNode.asInt() : Integer.MAX_VALUE;
    }));

    // 重新构建 properties
    ObjectNode sortedProperties = mapper.createObjectNode();
    for (Map.Entry<String, JsonNode> entry : fields) {
      sortedProperties.set(entry.getKey(), entry.getValue());
    }

    // 替换原来的 properties
    schema.set("properties", sortedProperties);

    //BaseUser details = userContext.getDetails();
    //Set<SimpleFieldPermissions> fields = formSchemaGenerator.loadCurrentUserSchemaPropertiesPermissions(details, domainClass.getName());

    return schema;
  }

  /**
   * Retrieves button declarations from the specified domain class.
   *
   * @param domainClass the domain class from which to retrieve button declarations
   * @return a set of maps representing button declaration attributes
   */
  protected Set<Map<String, Object>> getButtonDeclarationsSchema(Class<T> domainClass) {
    boolean annotationPresent = domainClass.isAnnotationPresent(ButtonDeclarations.class);
    if (annotationPresent) {
      ButtonDeclarations annotation = domainClass.getAnnotation(ButtonDeclarations.class);
      ButtonDeclaration[] buttonDeclarations = annotation.value();
      if (buttonDeclarations.length > 0) {
        Set<Map<String, Object>> result = new HashSet<>();
        for (ButtonDeclaration buttonDeclaration : buttonDeclarations) {
          result.add(extractAnnotationAttributes(buttonDeclaration));
        }
        return result;
      }
    }
    return Set.of();
  }

  /**
   * Extracts the attributes of the given annotation into a map.
   *
   * @param annotation the annotation from which to extract attributes
   * @return a map containing the annotation's attribute names and their corresponding values
   */
  protected Map<String, Object> extractAnnotationAttributes(Annotation annotation) {
    Map<String, Object> result = new HashMap<>();
    Method[] methods = annotation.annotationType().getDeclaredMethods();
    for (Method method : methods) {
      try {
        Object value = method.invoke(annotation);
        result.put(method.getName(), value);
      } catch (Exception e) {
        log.warn("Could not extract annotation attributes from method {}", method.getName(), e);
      }
    }
    return result;
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
  public <S extends T> S add(S entity) {
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
   */
  @Override
  public <S extends T> T modifyById(S entity) {
    if (entity == null) {
      throw new NullPointerException("entity is null");
    }
    if (entity.getId() == null) {
      throw new NullPointerException("entity id is null");
    }
    repository.findById(entity.getId()).ifPresent(db -> {
      // Only these fields are allowed to be modified by the client
      Set<String> scopeFields = getAllFieldNames(getRepository().getDomainClass()); // e.g. load from permissions if needed

      // For fields NOT in scopeFields, copy from db -> entity (including nulls)
      CopyOptions options = CopyOptions.create()
          .setIgnoreNullValue(false) // must overwrite with nulls from db to revert forbidden changes
          .setIgnoreError(true)
          .setFieldNameEditor(name -> scopeFields.contains(name) ? null : name); // null => skip copy for allowed fields

      BeanUtil.copyProperties(db, entity, options);

      // Audit diff between db (before) and entity (after merge)
      getModifyDataAuditingServices().forEach(svc -> svc.modify(db, entity, repository.getDomainClass()));
    });

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
   */
  @Override
  public <S extends T> Page<S> limit(Map<String, String> attributes, Pageable pageable) {
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
}
