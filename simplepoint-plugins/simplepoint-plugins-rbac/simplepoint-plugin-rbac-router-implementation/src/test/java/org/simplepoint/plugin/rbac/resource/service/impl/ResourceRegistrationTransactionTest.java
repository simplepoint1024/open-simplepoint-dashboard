package org.simplepoint.plugin.rbac.resource.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.security.ResourceDeclaration;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceAncestor;
import org.simplepoint.security.resource.ResourceRegistrationException;

/** Real JPA registration effects, rollback and ownership checks on H2 and PostgreSQL. */
class ResourceRegistrationTransactionTest extends ResourceCatalogTestSupport {
  @Test
  void registrationRenameAndRetirementRetainIdentityAndCleanAncestorRows() {
    ResourceDeclaration root = declaration("root", "/page", "Original");
    root.setChildren(Set.of(declaration("child", null, "Child")));
    service.sync("owner", Set.of(root));
    final Resource original = resource("root");
    final String childId = resource("child").getId();
    assertThat(
            read(
                () ->
                    em.createQuery("from ResourceAncestor", ResourceAncestor.class)
                        .getResultList()))
        .hasSize(1);
    service.sync("foreign", Set.of(declaration("foreign", "/foreign", "Foreign")));

    service.sync("owner", Set.of(declaration("renamed", "/page", "Renamed")));

    assertThat(resource("renamed").getId()).isEqualTo(original.getId());
    assertThat(resource("renamed").getName()).isEqualTo("Renamed");
    assertThat(read(() -> em.find(Resource.class, childId))).isNull();
    assertThat(
            read(
                () ->
                    em.createQuery("from ResourceAncestor", ResourceAncestor.class)
                        .getResultList()))
        .isEmpty();
    assertThat(resource("foreign").getPluginId()).isEqualTo("foreign");
    verify(catalogEffects).renamePlatformCode("root", "renamed");
    verify(catalogEffects).retire(Set.of(childId), Set.of("child"));
  }

  @Test
  void lateClosureConflictRollsBackEarlierResourceAndAncestorWrites() {
    ResourceDeclaration root = declaration("root", "/page", "Before");
    root.setChildren(Set.of(declaration("retired", null, "Retired")));
    service.sync("owner", Set.of(root));
    service.sync("foreign", Set.of(declaration("foreign", "/foreign", "Foreign")));
    String retiredId = resource("retired").getId();
    String foreignId = resource("foreign").getId();
    transaction.executeWithoutResult(
        status -> {
          ResourceAncestor stale = new ResourceAncestor();
          stale.setAncestorId(retiredId);
          stale.setChildId(foreignId);
          em.persist(stale);
        });

    assertThatThrownBy(
            () -> service.sync("owner", Set.of(declaration("root", "/page", "Must roll back"))))
        .isInstanceOf(ResourceRegistrationException.class);

    assertThat(resource("root").getName()).isEqualTo("Before");
    assertThat(resource("retired").getId()).isEqualTo(retiredId);
    assertThat(resource("foreign").getId()).isEqualTo(foreignId);
    assertThat(
            read(
                () ->
                    em.createQuery("from ResourceAncestor", ResourceAncestor.class)
                        .getResultList()))
        .hasSize(2);
  }

  @Test
  void foreignPathCollisionLeavesPersistedCatalogUnchanged() {
    service.sync("foreign", Set.of(declaration("foreign", "/foreign", "Foreign")));
    assertThatThrownBy(
            () -> service.sync("owner", Set.of(declaration("new", "/foreign", "Replacement"))))
        .isInstanceOf(ResourceRegistrationException.class);
    assertThat(read(() -> em.createQuery("from Resource", Resource.class).getResultList()))
        .singleElement()
        .satisfies(
            resource -> {
              assertThat(resource.getCode()).isEqualTo("foreign");
              assertThat(resource.getPluginId()).isEqualTo("foreign");
            });
  }
}
