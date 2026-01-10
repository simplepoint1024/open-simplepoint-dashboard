package org.simplepoint.data.jpa.base.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.core.base.entity.impl.BaseEntityImpl;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.data.jpa.AttributeMatcher;
import org.simplepoint.data.jpa.AttributeMatchers;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
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
  public List<T> findAll(Map<String, String> attributes) {
    return this.limit(attributes, Pageable.unpaged()).getContent();
  }

  @Override
  @SuppressWarnings("unchecked")
  public <S extends T> Page<S> limit(Map<String, String> attributes, Pageable pageable) {
//    Session session = entityManager.unwrap(Session.class);
//    session.enableFilter("tenantFilter").setParameter("tenantId","0000");
    Specification<S> spec = this.readSpecification(attributes);
    Class<S> domainClass = (Class<S>) super.getDomainClass();
    var typedQuery = this.getQuery(spec, domainClass, pageable);
    return (pageable.isUnpaged() ? new PageImpl<>(typedQuery.getResultList()) :
        this.readPage(typedQuery, domainClass, pageable, spec));
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
    return (root, query, criteriaBuilder) -> criteriaBuilder.and(
        getPredicates(attributes, root, query, criteriaBuilder, EscapeCharacter.DEFAULT));
  }

  @Override
  public <S extends T> long count(S example) {
    return super.count(Example.of(example));
  }

  @SneakyThrows
  private <S extends T> Predicate[] getPredicates(
      Map<String, String> attributes,
      Root<S> root,
      CriteriaQuery<?> query,
      CriteriaBuilder criteriaBuilder,
      EscapeCharacter character
  ) {
    Metamodel metamodel = this.entityManager.getMetamodel();
    EntityType<T> entityType = metamodel.entity(getDomainClass());
    Set<String> entityAttribute = entityType.getAttributes().stream().map(Attribute::getName).collect(Collectors.toSet());
    List<Predicate> predicates = new ArrayList<>();
    if (attributes != null && !attributes.isEmpty()) {
      attributes.forEach((name, value) -> {
        try {
          if (value != null & entityAttribute.contains(name)) {
            if (value.contains(":")) {
              String[] strings = StringUtil.splitLast(value, ":");
              AttributeMatcher attributeMatcher = AttributeMatchers.getAttributeMatcher(strings[0]);
              if (attributeMatcher != null) {
                attributeMatcher.match(name, strings[1], root, query, criteriaBuilder, character,
                    predicates);
              }
            } else {
              AttributeMatchers.equals()
                  .match(name, value, root, query, criteriaBuilder, character, predicates);
            }
          }
        } catch (Exception e) {
          log.warn(e.getMessage());
        }
      });
    }
    return predicates.toArray(new Predicate[0]);
  }
}
