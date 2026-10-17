package org.simplepoint.data.jpa.base.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.Attribute;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.AuthorizationScopeGuards;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.core.base.entity.impl.TenantBaseEntityImpl;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.core.datascopeannotation.DataScopeContext;
import org.simplepoint.core.query.FilterCondition;
import org.simplepoint.core.query.InvalidQueryException;
import org.simplepoint.core.query.QueryCapabilities;
import org.simplepoint.core.query.QueryDocument;
import org.simplepoint.data.jpa.AttributeMatchers;
import org.simplepoint.data.jpa.DataScopePredicates;
import org.simplepoint.data.jpa.StructuredQuery;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.convert.QueryByExamplePredicateBuilder;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.query.EscapeCharacter;
import org.springframework.data.jpa.repository.support.JpaEntityInformation;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * A generic implementation of the BaseRepository interface.
 * This class extends SimpleJpaRepository to provide custom repository
 * functionality, including CRUD operations, batch processing, and dynamic queries.
 *
 * @param <T> the type of the entity
 * @param <I> the type of the entity ID, which must be serializable
 */
@Slf4j
public class BaseRepositoryImpl<T extends BaseEntityImpl<I>, I extends Serializable>
    extends SimpleJpaRepository<T, I> implements BaseRepository<T, I> {

  private static final String TENANT_ID_ATTRIBUTE = "X-Tenant-Id";
  private static final String TENANT_CONTEXT_REQUIRED_MESSAGE =
      "Tenant-aware repository operation requires an active tenant context";

  private final EntityManager entityManager;

  /**
   * Constructs a new BaseRepositoryImpl instance.
   * This constructor initializes the repository with entity information and an entity manager.
   *
   * @param entityInformation the metadata information for the entity
   * @param entityManager     the entity manager used for persistence operations
   */
  public BaseRepositoryImpl(JpaEntityInformation<T, ?> entityInformation,
                            EntityManager entityManager) {
    super(entityInformation, entityManager);
    this.entityManager = entityManager;
  }

  /**
   * Constructs a new BaseRepositoryImpl instance.
   * This constructor initializes the repository with an entity class and an entity manager.
   *
   * @param domainClass the class type of the entity
   * @param em          the entity manager used for persistence operations
   */
  public BaseRepositoryImpl(Class<T> domainClass, EntityManager em) {
    super(domainClass, em);
    this.entityManager = em;
  }

  /**
   * Constructs a new BaseRepositoryImpl instance.
   * This constructor initializes the repository with an entity class and an entity manager.
   */
  @Override
  public Class<T> getDomainClass() {
    return super.getDomainClass();
  }

  @Override
  public void flush() {
    super.flush();
  }

  @Override
  @Modifying
  @Transactional
  public <S extends T> T updateById(S entity) {
    super.save(entity);
    return entity;
  }

  @Override
  @Modifying
  @Transactional
  public void deleteAll() {
    super.deleteAll();
  }

  @Override
  @Modifying
  @Transactional
  public void deleteById(I id) {
    super.deleteById(id);
  }

  @Override
  @Modifying
  @Transactional
  public void deleteByIds(Collection<I> ids) {
    super.deleteAllByIdInBatch(ids);
  }

  @Override
  public List<T> findAllByIds(Iterable<I> ids) {
    return super.findAllById(ids);
  }

  @Override
  public List<T> findAll(Map<String, String> attributes) throws IllegalArgumentException {
    return this.limit(attributes, Pageable.unpaged()).getContent();
  }

  @Override
  public <S extends T> Page<S> limit(Map<String, String> attributes, Pageable pageable)
      throws IllegalArgumentException {
    return limitScoped(attributes, Map.of(), pageable);
  }

  @Override
  public <S extends T> Page<S> limitScoped(
      Map<String, String> attributes, Map<String, String> required, Pageable pageable) {
    // Snapshot server restrictions before constructing both the result and count specifications.
    Map<String, String> mandatory = Map.copyOf(required);
    validateServerConditions(mandatory);
    Specification<S> restriction = mandatory.isEmpty() ? null : (root, query, cb) ->
        cb.and(getPredicates(mandatory, root, query, cb, EscapeCharacter.DEFAULT, true));
    return limitWithRestriction(attributes, pageable, restriction);
  }

  private static void validateServerConditions(Map<String, String> mandatory) {
    mandatory.forEach((field, value) -> {
      if (Set.of("page", "size", "sort", QueryDocument.PARAMETER).contains(field)
          || value.startsWith("{")) {
        throw new IllegalArgumentException("Server restrictions must be scalar conditions");
      }
    });
  }

  @Override
  public <S extends T> Page<S> limitInScope(
      Map<String, String> attributes, String field, Collection<String> allowedValues, Pageable pageable) {
    return limitMemberships(attributes, List.of(new org.simplepoint.api.base.QueryMembership(
        field, new ArrayList<>(allowedValues), true)), pageable);
  }

  @Override
  public <S extends T> Page<S> limitMemberships(
      Map<String, String> attributes, List<org.simplepoint.api.base.QueryMembership> memberships, Pageable pageable) {
    var restrictions = List.copyOf(memberships);
    restrictions.forEach(restriction -> validateMembershipField(restriction.field()));
    return limitWithRestriction(attributes, pageable, (root, query, cb) -> cb.and(
        restrictions.stream().map(restriction -> {
          if (restriction.values().isEmpty()) {
            return restriction.included() ? cb.disjunction() : cb.conjunction();
          }
          var membership = root.get(restriction.field()).in(restriction.values());
          return restriction.included() ? membership : cb.not(membership);
        }).toArray(Predicate[]::new)));
  }

  private void validateMembershipField(String field) {
    validateStringField(getDomainClass(), field);
  }

  private void validateStringField(Class<?> type, String field) {
    var member = entityManager.getMetamodel().entity(type).getAttribute(field);
    // Hibernate may expose the erased Serializable type for a generic BaseEntity<I>.id.
    Class<?> scalarType = member.getJavaMember() instanceof java.lang.reflect.Field javaField
        ? org.springframework.core.ResolvableType.forField(javaField, type).resolve()
        : member.getJavaType();
    if (member.isAssociation() || member.isCollection() || scalarType != String.class) {
      throw new IllegalArgumentException("Authorization scope requires a scalar string field");
    }
  }

  @Override
  public <S extends T> Page<S> limitReferencedScoped(
      Map<String, String> attributes, Map<String, String> required,
      List<org.simplepoint.api.base.QueryReferenceMembership> memberships, Pageable pageable) {
    var mandatory = Map.copyOf(required);
    validateServerConditions(mandatory);
    var references = List.copyOf(memberships);
    var types = new java.util.LinkedHashMap<String, Class<?>>();
    for (var reference : references) {
      validateStringField(getDomainClass(), reference.field());
      Class<?> type = entityManager.getMetamodel().getEntities().stream()
          .filter(entity -> entity.getName().equals(reference.entityName()))
          .map(jakarta.persistence.metamodel.EntityType::getJavaType).findFirst()
          .orElseThrow(() -> new IllegalArgumentException("Unknown reference entity"));
      if (!BaseEntityImpl.class.isAssignableFrom(type)) {
        throw new IllegalArgumentException("Reference entity must support soft deletion");
      }
      validateStringField(type, reference.referenceField());
      reference.equalities().forEach((field, value) -> {
        var member = entityManager.getMetamodel().entity(type).getAttribute(field);
        if (Set.of("deletedAt", "tenantId").contains(field) || member.isAssociation()
            || member.isCollection() || !member.getJavaType().isInstance(value)
            || !(value instanceof String || value instanceof Boolean)) {
          throw new IllegalArgumentException("Reference conditions must be typed scalar literals");
        }
      });
      types.put(reference.entityName(), type);
    }
    // Membership reads are server-owned, not public content reads: hidden grants must still exclude
    // duplicate assignments. Outer records retain their normal tenant, field and data-scope policies.
    return limitWithRestriction(attributes, pageable, (root, query, cb) -> {
      var predicates = new ArrayList<>(Arrays.asList(
          getPredicates(mandatory, root, query, cb, EscapeCharacter.DEFAULT, true)));
      for (var reference : references) {
        var subquery = query.subquery(Integer.class);
        Class<?> type = types.get(reference.entityName());
        var target = subquery.from(type);
        var conditions = new ArrayList<Predicate>();
        conditions.add(cb.equal(target.get(reference.referenceField()), root.get(reference.field())));
        conditions.add(cb.isNull(target.get("deletedAt")));
        reference.equalities().forEach((field, value) -> conditions.add(cb.equal(target.get(field), value)));
        if (TenantBaseEntityImpl.class.isAssignableFrom(type)) {
          var context = AuthorizationContextHolder.getContext();
          String tenant = context == null ? null : context.getAttribute(TENANT_ID_ATTRIBUTE);
          if (tenant == null || tenant.isBlank()) {
            throw new IllegalStateException(TENANT_CONTEXT_REQUIRED_MESSAGE);
          }
          conditions.add(cb.equal(target.get("tenantId"), tenant.trim()));
        }
        subquery.select(cb.literal(1)).where(conditions.toArray(Predicate[]::new));
        predicates.add(reference.included() ? cb.exists(subquery) : cb.not(cb.exists(subquery)));
      }
      return cb.and(predicates.toArray(Predicate[]::new));
    });
  }

  @SuppressWarnings("unchecked")
  private <S extends T> Page<S> limitWithRestriction(
      Map<String, String> attributes, Pageable pageable, Specification<S> restriction) {
    QueryDocument.validateBudget(attributes);
    var sort = pageable.getSort();
    if (sort.stream().count() > 3) {
      throw new InvalidQueryException("最多支持三个排序字段");
    }
    for (var order : sort) {
      if (!order.getProperty().matches("[A-Za-z][A-Za-z0-9_]*")
          || !QueryCapabilities.readable(getDomainClass(), order.getProperty())) {
        throw new InvalidQueryException("不支持的排序字段");
      }
      if (QueryCapabilities.declaration(getDomainClass()) != null) {
        QueryCapabilities.require(getDomainClass(), order.getProperty(), null, true);
      } else {
        if (entityManager.getMetamodel().entity(getDomainClass()).getAttributes().stream()
            .noneMatch(attribute -> attribute.getName().equals(order.getProperty()))) {
          throw new InvalidQueryException("不支持的排序字段");
        }
        var attribute =
            entityManager.getMetamodel().entity(getDomainClass()).getAttribute(order.getProperty());
        if (attribute.isAssociation() || attribute.isCollection()) {
          throw new InvalidQueryException("不支持关联排序");
        }
      }
    }
    if (sort.isUnsorted()) {
      sort = org.simplepoint.core.query.DefaultTableOrder.forModel(getDomainClass(), name ->
          org.simplepoint.core.query.DefaultTableOrder.exists(getDomainClass(), name)
              && QueryCapabilities.readable(getDomainClass(), name)
              && entityManager.getMetamodel().entity(getDomainClass()).getAttributes().stream()
                  .anyMatch(attribute -> attribute.getName().equals(name) && !attribute.isAssociation() && !attribute.isCollection()));
    }
    // A unique tie-breaker prevents row drift when equal sort values cross page boundaries.
    if (sort.getOrderFor("id") == null) {
      sort = sort.and(org.springframework.data.domain.Sort.by("id"));
    }
    if (pageable.isPaged()) {
      if ((QueryCapabilities.declaration(getDomainClass()) != null
              && pageable.getPageSize()
                  > QueryCapabilities.declaration(getDomainClass()).maxPageSize())
          || pageable.getOffset() > Integer.MAX_VALUE) {
        throw new InvalidQueryException("分页范围超限");
      }
      pageable =
          org.springframework.data.domain.PageRequest.of(
              pageable.getPageNumber(), pageable.getPageSize(), sort);
    } else {
      pageable = Pageable.unpaged(sort);
    }
    Specification<S> visible = this.readSpecification(attributes);
    Specification<S> spec = restriction == null ? visible : visible.and(restriction);
    Class<S> domainClass = (Class<S>) super.getDomainClass();
    var stableSort = sort;
    Specification<S> orderedSpec =
        (root, query, cb) -> {
          var predicate = spec.toPredicate(root, query, cb);
          var orders = new ArrayList<jakarta.persistence.criteria.Order>();
          stableSort.forEach(
              order -> {
                var path = org.simplepoint.data.jpa.QueryExpressions.scalar(cb, root.get(order.getProperty()));
                orders.add(cb.asc(cb.selectCase().when(cb.isNull(path), 1).otherwise(0)));
                orders.add(order.isAscending() ? cb.asc(path) : cb.desc(path));
              });
          query.orderBy(orders);
          return predicate;
        };
    var typedQuery =
        this.getQuery(orderedSpec, domainClass, org.springframework.data.domain.Sort.unsorted());
    return (pageable.isUnpaged()
        ? new PageImpl<>(typedQuery.getResultList())
        : this.readPage(typedQuery, domainClass, pageable, spec));
  }

  @Override
  public <S extends T> boolean exists(S example) {
    return count(example) > 0;
  }

  /**
   * Reads and creates a Specification based on the provided attributes.
   *
   * @param attributes the attributes for dynamic query building
   * @param <S>        the type of the entity
   * @return the Specification object
   */
  protected <S extends T> Specification<S> readSpecification(Map<String, String> attributes) {
    enableTenantFilter();
    DataScopeCondition scope = DataScopeContext.get();
    return (root, query, criteriaBuilder) -> {
      List<Predicate> predicates = new ArrayList<>(Arrays.asList(
          getPredicates(attributes, root, query, criteriaBuilder, EscapeCharacter.DEFAULT)));
      if (scope != null) {
        Predicate dataScopePredicate = DataScopePredicates.compile(scope, root, criteriaBuilder,
            entityManager.getMetamodel());
        if (dataScopePredicate != null) {
          predicates.add(dataScopePredicate);
        }
      }
      return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
    };
  }

  @Override
  public <S extends T> long count(S example) {
    enableTenantFilter();
    DataScopeCondition scope = DataScopeContext.get();
    Example<T> typedExample = Example.of((T) example);
    Specification<T> specification = (root, query, cb) -> {
      Predicate examplePredicate = QueryByExamplePredicateBuilder.getPredicate(root, cb, typedExample);
      Predicate dataScopePredicate = scope == null ? null : DataScopePredicates.compile(scope, root, cb,
          entityManager.getMetamodel());
      List<Predicate> predicates = new ArrayList<>();
      predicates.add(cb.isNull(root.get("deletedAt")));
      if (examplePredicate != null) {
        predicates.add(examplePredicate);
      }
      if (dataScopePredicate != null) {
        predicates.add(dataScopePredicate);
      }
      return cb.and(predicates.toArray(new Predicate[0]));
    };
    return super.count(specification);
  }

  private <S extends T> Predicate[] getPredicates(
      Map<String, String> attributes, Root<S> root, CriteriaQuery<?> query,
      CriteriaBuilder criteriaBuilder, EscapeCharacter character) {
    return getPredicates(attributes, root, query, criteriaBuilder, character, false);
  }

  private <S extends T> Predicate[] getPredicates(
      Map<String, String> attributes, Root<S> root, CriteriaQuery<?> query,
      CriteriaBuilder criteriaBuilder, EscapeCharacter character, boolean serverOwned) {
    List<Predicate> predicates = new ArrayList<>();
    if (attributes == null || attributes.isEmpty()) {
      return new Predicate[0];
    }
    if (attributes.size() > 35) {
      throw new InvalidQueryException("筛选条件数量超限");
    }
    Set<String> operators = AttributeMatchers.operators();
    var entity = entityManager.getMetamodel().entity(getDomainClass());
    Set<String> fields =
        entity.getAttributes().stream().map(Attribute::getName).collect(Collectors.toSet());
    for (var entry : attributes.entrySet()) {
      String name = entry.getKey();
      if (name == null) {
        throw new InvalidQueryException("筛选字段不能为空");
      }
      if (QueryDocument.PARAMETER.equals(name)) {
        predicates.add(
            StructuredQuery.compile(
                criteriaBuilder, query, root, QueryDocument.parse(entry.getValue()).where()));
        continue;
      }
      // Spring MVC passes these transport parameters alongside filters, even when an entity
      // has a same-named member (Resource.sort). Such members use the structured $query document.
      if (Set.of("page", "size", "sort").contains(name)) {
        continue;
      }
      if (!fields.contains(name)) {
        throw new InvalidQueryException("不支持的筛选字段");
      }
      if (entry.getValue() != null && entry.getValue().startsWith("{")) {
        var node = QueryDocument.parse(entry.getValue()).where();
        if (!name.equals(node.field()) || !node.children().isEmpty()) {
          throw new InvalidQueryException("无效的列筛选");
        }
        predicates.add(StructuredQuery.compile(criteriaBuilder, query, root, node));
        continue;
      }
      var attribute = entity.getAttribute(name);
      if (attribute.isCollection() || attribute.isAssociation()) {
        throw new InvalidQueryException("关联和集合字段须使用专用查询");
      }
      var condition = FilterCondition.parse(entry.getValue(), operators);
      // deletedAt is injected/overwritten by BaseService and cannot be weakened by the caller.
      if (!serverOwned && !name.equals("deletedAt")) {
        if (!QueryCapabilities.readable(getDomainClass(), name)) {
          throw new InvalidQueryException("此字段未开放查询");
        }
      }
      var matcher = AttributeMatchers.getAttributeMatcher(condition.operator());
      if (matcher == null) {
        throw new InvalidQueryException("不支持的筛选操作符");
      }
      try {
        int before = predicates.size();
        matcher.match(name, condition.value(), root, query, criteriaBuilder, character, predicates);
        if (predicates.size() == before) {
          throw new InvalidQueryException("筛选操作未生成有效条件");
        }
      } catch (InvalidQueryException error) {
        throw error;
      } catch (Exception error) {
        throw new InvalidQueryException("筛选条件与字段类型不匹配");
      }
    }
    return predicates.toArray(new Predicate[0]);
  }

  @Override
  public void enableTenantFilter() {
    if (TenantBaseEntityImpl.class.isAssignableFrom(getDomainClass())) {
      AuthorizationContext context = AuthorizationContextHolder.getContext();
      if (context == null) {
        throw new IllegalStateException(TENANT_CONTEXT_REQUIRED_MESSAGE);
      }
      String tenantId = context.getAttribute(TENANT_ID_ATTRIBUTE);
      if (tenantId == null || tenantId.isBlank()) {
        if (AuthorizationScopeGuards.isPlatformAdministrator(context)) {
          return;
        }
        throw new IllegalStateException(TENANT_CONTEXT_REQUIRED_MESSAGE);
      }
      Session session = entityManager.unwrap(Session.class);
      session.enableFilter("tenantFilter").setParameter("tenantId", tenantId.trim());
    }
  }
}
