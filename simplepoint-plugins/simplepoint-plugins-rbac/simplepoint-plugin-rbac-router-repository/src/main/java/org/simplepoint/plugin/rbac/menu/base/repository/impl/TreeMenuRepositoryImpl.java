package org.simplepoint.plugin.rbac.menu.base.repository.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.simplepoint.plugin.rbac.menu.api.repository.TreeMenuRepository;
import org.simplepoint.security.entity.Menu;
import org.springframework.stereotype.Repository;

/**
 * Custom fragment implementation for {@link TreeMenuRepository}.
 *
 * <p>Spring Data will automatically compose this implementation into any repository
 * that extends {@code TreeMenuRepository} (e.g., {@code JpaMenuRepository}),
 * because the class name matches the fragment interface name with the suffix "Impl".</p>
 *
 * <p>Note: Do NOT annotate this class with {@code @Repository}. It will be picked up
 * by Spring Data via repository composition.</p>
 */
@Repository
public class TreeMenuRepositoryImpl implements TreeMenuRepository {

  @PersistenceContext
  private EntityManager em;

  @Override
  public Collection<Menu> findInPathStartingWith(Collection<String> pathSuffixes) {
    if (pathSuffixes == null || pathSuffixes.isEmpty()) {
      return Collections.emptyList();
    }

    CriteriaBuilder cb = em.getCriteriaBuilder();
    CriteriaQuery<Menu> cq = cb.createQuery(Menu.class);
    Root<Menu> root = cq.from(Menu.class);

    List<Predicate> likes = new ArrayList<>(pathSuffixes.size());
    for (String suffix : pathSuffixes) {
      if (suffix == null || suffix.isBlank()) {
        continue;
      }
      likes.add(cb.like(root.get("path"), suffix + "%"));
    }

    if (likes.isEmpty()) {
      return Collections.emptyList();
    }

    cq.select(root).where(cb.or(likes.toArray(new Predicate[0])));
    return em.createQuery(cq).getResultList();
  }
}

