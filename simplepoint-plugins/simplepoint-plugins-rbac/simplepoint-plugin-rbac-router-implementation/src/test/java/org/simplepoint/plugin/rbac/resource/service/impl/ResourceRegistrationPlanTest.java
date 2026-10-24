package org.simplepoint.plugin.rbac.resource.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplepoint.security.resource.ResourceRegistrationException.Code.HIERARCHY_CONFLICT;
import static org.simplepoint.security.resource.ResourceRegistrationException.Code.IDENTITY_CONFLICT;
import static org.simplepoint.security.resource.ResourceRegistrationException.Code.INVALID_DECLARATION;
import static org.simplepoint.security.resource.ResourceRegistrationException.Code.OWNERSHIP_CONFLICT;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.security.ResourceDeclaration;
import org.simplepoint.security.ResourceScopePolicy;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceScopeType;
import org.simplepoint.security.resource.ResourceRegistrationException;

class ResourceRegistrationPlanTest {
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"other", " other "})
  void cannotAdoptForeignOrUnownedRecordsByEitherCodeOrPath(String existingOwner) {
    Resource existing = stored("id", "record.view", "/record", existingOwner, null);
    rejected(OWNERSHIP_CONFLICT, List.of(existing), declaration("record.view", "/new"));
    rejected(OWNERSHIP_CONFLICT, List.of(existing), declaration("new.view", "/record"));
  }

  @Test
  void checksBothIdentitiesEvenWhenCodeAlreadyMatchesTheOwner() {
    Resource own = stored("own", "own.view", "/own", "owner", null);
    Resource foreign = stored("foreign", "foreign.view", "/foreign", "foreign", null);
    rejected(OWNERSHIP_CONFLICT, List.of(own, foreign), declaration("own.view", "/foreign"));
    foreign.setPluginId("owner");
    rejected(IDENTITY_CONFLICT, List.of(own, foreign), declaration("own.view", "/foreign"));
  }

  @Test
  void sameOwnerRenameRetainsIdentityAndPermissionMetadataWithoutMutatingInputs() {
    Resource existing = stored("stable-id", "old.view", "/page", "owner", null);
    existing.setPermissionMetadata("{\"models\":[]}");
    ResourceDeclaration input = declaration("new.view", "/page");
    ResourceRegistrationPlan plan = plan(List.of(existing), input);
    assertThat(plan.entries()).hasSize(1);
    Resource proposed = plan.entries().getFirst().resource();
    assertThat(proposed.getId()).isEqualTo("stable-id");
    assertThat(proposed.getPermissionMetadata()).isEqualTo(existing.getPermissionMetadata());
    assertThat(proposed.getPluginId()).isEqualTo("owner");
    assertThat(plan.retiredIds()).isEmpty();
    assertThat(existing.getCode()).isEqualTo("old.view");
    assertThat(input.getPluginId()).isNull();
    assertThat(input.getPermissionMetadata()).isNull();
    input.setPermissionMetadata("changed-after-prepare");
    assertThat(proposed.getPermissionMetadata()).isEqualTo("{\"models\":[]}");
  }

  @Test
  void cannotClaimOneStoredRecordTwiceUsingItsCodeAndItsPath() {
    Resource existing = stored("id", "old", "/page", "owner", null);
    rejected(
        IDENTITY_CONFLICT,
        List.of(existing),
        declaration("old", null),
        declaration("renamed", "/page"));
  }

  @Test
  void cannotOverrideOwnerInsideNestedDeclarations() {
    ResourceDeclaration root = declaration("root", null);
    ResourceDeclaration child = declaration("child", null);
    child.setPluginId("foreign");
    root.setChildren(Set.of(child));
    rejected(OWNERSHIP_CONFLICT, List.of(), root);
  }

  @Test
  void duplicateCodesOrPathsAreRejectedAcrossTheWholeTree() {
    ResourceDeclaration root = declaration("root", "/root");
    root.setChildren(Set.of(declaration("child", "/child")));
    rejected(IDENTITY_CONFLICT, List.of(), root, declaration("child", "/another"));
    rejected(IDENTITY_CONFLICT, List.of(), root, declaration("another", "/child"));
  }

  @Test
  void inheritedScopesAndNestedParentsArePreparedBeforePersistence() {
    ResourceDeclaration root = declaration("root", null);
    root.setScopeTypes(Set.of(ResourceScopeType.PLATFORM, ResourceScopeType.TENANT));
    ResourceDeclaration child = declaration("child", null);
    root.setChildren(Set.of(child));
    ResourceRegistrationPlan plan = plan(List.of(), root);
    assertThat(plan.entries())
        .extracting(entry -> entry.resource().getCode())
        .containsExactly("root", "child");
    assertThat(plan.entries().getLast().parentCode()).isEqualTo("root");
    assertThat(plan.entries().getLast().resource().getScopeTypes()).isEqualTo(root.getScopeTypes());
    assertThat(child.getScopeTypes()).isNull();
    child.setScopeTypes(Set.of(ResourceScopeType.PERSONAL));
    rejected(HIERARCHY_CONFLICT, List.of(), root);
  }

  @Test
  void physicalParentReferencesMustBeOwnedRetainedAndAcyclic() {
    Resource parent = stored("parent-id", "parent", null, "owner", null);
    Resource child = stored("child-id", "child", null, "owner", "parent-id");
    ResourceDeclaration parentInput = declaration("parent", null);
    ResourceDeclaration childInput = declaration("child", null);
    childInput.setParentId("parent-id");
    ResourceRegistrationPlan plan = plan(List.of(parent, child), childInput, parentInput);
    assertThat(plan.entries())
        .extracting(entry -> entry.resource().getCode())
        .containsExactly("parent", "child");
    rejected(HIERARCHY_CONFLICT, List.of(parent, child), childInput);
    parent.setPluginId("foreign");
    rejected(OWNERSHIP_CONFLICT, List.of(parent, child), childInput);
    parent.setPluginId("owner");
    parentInput.setParentId("child-id");
    rejected(HIERARCHY_CONFLICT, List.of(parent, child), parentInput, childInput);
    parentInput.setParentId("absent");
    rejected(HIERARCHY_CONFLICT, List.of(parent, child), parentInput, childInput);
  }

  @Test
  void retirementCannotCascadeIntoForeignDescendantsEvenWithoutClosureRows() {
    Resource parent = stored("parent", "retired", null, "owner", null);
    Resource middle = stored("middle", "middle", null, "owner", "parent");
    Resource foreign = stored("foreign", "foreign", null, "foreign", "middle");
    rejected(OWNERSHIP_CONFLICT, List.of(parent, middle, foreign), declaration("new", null));
  }

  @Test
  void foreignDescendantsPreventIndirectReparentingAndScopeChanges() {
    Resource parent = stored("parent", "parent", null, "owner", null);
    Resource foreign = stored("foreign", "foreign", null, "other", "parent");
    ResourceDeclaration parentInput = declaration("parent", null);
    assertThat(plan(List.of(parent, foreign), parentInput).retiredIds()).isEmpty();
    parentInput.setScopeTypes(Set.of(ResourceScopeType.PLATFORM));
    rejected(HIERARCHY_CONFLICT, List.of(parent, foreign), parentInput);
    parentInput.setScopeTypes(null);
    ResourceDeclaration newRoot = declaration("new-root", null);
    newRoot.setChildren(Set.of(parentInput));
    rejected(HIERARCHY_CONFLICT, List.of(parent, foreign), newRoot);
  }

  @Test
  void retirementOnlySelectsUnmatchedOwnedRecordsAndRetainedNodesCanBeMoved() {
    Resource retired = stored("retired", "retired", null, "owner", null);
    Resource retained = stored("retained", "retained", null, "owner", "retired");
    Resource foreign = stored("foreign", "foreign", null, "other", null);
    var plan = plan(List.of(retired, retained, foreign), declaration("retained", null));
    assertThat(plan.retiredIds()).containsExactly("retired");
    assertThat(plan.entries().getFirst().parentCode()).isNull();
  }

  @Test
  void malformedAndOversizedDeclarationsFailWithBoundedTraversal() {
    rejected(INVALID_DECLARATION, List.of(), declaration(" ", null));
    rejected(INVALID_DECLARATION, List.of(), declaration("valid", ""));
    rejected(INVALID_DECLARATION, List.of(), declaration("x".repeat(121), null));
    rejected(INVALID_DECLARATION, List.of(), (ResourceDeclaration) null);
    ResourceDeclaration root = declaration("root", null);
    Set<ResourceDeclaration> cycle = Collections.newSetFromMap(new IdentityHashMap<>());
    cycle.add(root);
    root.setChildren(cycle);
    assertThatThrownBy(
            () ->
                ResourceRegistrationPlan.prepare(
                    "owner", cycle, List.of(), ResourceRegistrationPlanTest::normalize))
        .isInstanceOf(ResourceRegistrationException.class);
    ResourceDeclaration deep = declaration("deep", null);
    ResourceDeclaration cursor = deep;
    for (int i = 0; i < 65; i++) {
      ResourceDeclaration child = declaration("deep." + i, null);
      cursor.setChildren(Set.of(child));
      cursor = child;
    }
    rejected(INVALID_DECLARATION, List.of(), deep);
    List<ResourceDeclaration> wide = new ArrayList<>();
    for (int i = 0; i <= 10_000; i++) {
      wide.add(declaration("wide." + i, null));
    }
    rejected(INVALID_DECLARATION, List.of(), wide.toArray(ResourceDeclaration[]::new));
  }

  @Test
  void explicitSharedParentMountPreservesForeignOwnershipAndExistingIdentity() {
    Resource parent = sharedParent("parent", null);
    parent.setScopeTypes(Set.of(ResourceScopeType.TENANT));
    Resource existing = stored("child-id", "child", "/child", "owner", null);
    ResourceDeclaration child = mounted("child", "parent");
    child.setPath("/child");
    var result = plan(List.of(parent, existing), child);
    var entry = result.entries().getFirst();
    assertThat(entry.externalParentId()).isEqualTo("parent");
    assertThat(entry.resource().getId()).isEqualTo("child-id");
    assertThat(entry.resource().getPluginId()).isEqualTo("owner");
    assertThat(entry.resource().getScopeTypes()).containsExactly(ResourceScopeType.TENANT);
    assertThat(result.retiredIds()).isEmpty();
    assertThat(existing.getParentId()).isNull();
    existing.setParentId("parent");
    assertThat(plan(List.of(parent, existing), child).entries().getFirst().resource().getId())
        .isEqualTo("child-id");
  }

  @Test
  void mountsRejectMissingWrongOwnedExecutableDisabledAndScopeEscalatingParents() {
    ResourceDeclaration child = mounted("child", "parent");
    rejected(OWNERSHIP_CONFLICT, List.of(), child);
    Resource parent = sharedParent("parent", null);
    parent.setPluginId("wrong-owner");
    rejected(OWNERSHIP_CONFLICT, List.of(parent), child);
    parent.setPluginId("other");
    parent.setSharedDirectory(false);
    rejected(HIERARCHY_CONFLICT, List.of(parent), child);
    parent.setSharedDirectory(true);
    parent.setComponent("app/Page");
    rejected(HIERARCHY_CONFLICT, List.of(parent), child);
    parent.setComponent(null);
    parent.setDisabled(true);
    rejected(HIERARCHY_CONFLICT, List.of(parent), child);
    parent.setDisabled(false);
    parent.setScopeTypes(Set.of(ResourceScopeType.TENANT));
    child.setScopeTypes(Set.of(ResourceScopeType.PLATFORM));
    rejected(HIERARCHY_CONFLICT, List.of(parent), child);
    child.setScopeTypes(null);
    child.setParentId("parent");
    rejected(HIERARCHY_CONFLICT, List.of(parent), child);
  }

  @Test
  void mergedForeignHierarchyRejectsCyclesEvenWhenRetainedNodeIsRenamed() {
    Resource root = stored("root-id", "old-root", "/root", "owner", null);
    Resource parent = sharedParent("foreign", "root-id");
    ResourceDeclaration renamed = mounted("new-root", "foreign");
    renamed.setPath("/root");
    rejected(HIERARCHY_CONFLICT, List.of(root, parent), renamed);
  }

  @Test
  void sharedDirectoryCannotBecomeExecutableOrBeRevokedUnderForeignChildren() {
    ResourceDeclaration directory = declaration("directory", null);
    directory.setType(org.simplepoint.security.entity.ResourceType.GROUP);
    directory.setSharedDirectory(true);
    directory.setGrantable(true);
    rejected(INVALID_DECLARATION, List.of(), directory);
    directory.setGrantable(false);
    Resource parent = sharedParent("directory", null);
    parent.setPluginId("owner");
    Resource child = stored("child", "child", null, "other", "directory");
    assertThat(plan(List.of(parent, child), directory).entries()).hasSize(1);
    directory.setSharedDirectory(false);
    rejected(HIERARCHY_CONFLICT, List.of(parent, child), directory);
  }

  private static Resource sharedParent(String id, String parentId) {
    var parent = stored(id, id, null, "other", parentId);
    parent.setType(org.simplepoint.security.entity.ResourceType.GROUP);
    parent.setGrantable(false);
    parent.setSharedDirectory(true);
    return parent;
  }

  private static ResourceDeclaration mounted(String code, String parent) {
    var child = declaration(code, null);
    child.setParentReference(new org.simplepoint.security.ResourceParentReference("other", parent));
    return child;
  }

  private static ResourceRegistrationPlan plan(
      List<Resource> existing, ResourceDeclaration... items) {
    // Identity set also lets the direct API test reject cycles/nulls without hashing caller graphs.
    Set<ResourceDeclaration> input = Collections.newSetFromMap(new IdentityHashMap<>());
    Collections.addAll(input, items);
    return ResourceRegistrationPlan.prepare(
        "owner", input, existing, ResourceRegistrationPlanTest::normalize);
  }

  private static void normalize(Resource resource) {
    resource.setScopeTypes(ResourceScopePolicy.effectiveScopes(resource.getScopeTypes()));
  }

  private static void rejected(
      ResourceRegistrationException.Code code,
      List<Resource> existing,
      ResourceDeclaration... declarations) {
    assertThatThrownBy(() -> plan(existing, declarations))
        .isInstanceOfSatisfying(
            ResourceRegistrationException.class,
            failure -> assertThat(failure.code()).isEqualTo(code));
  }

  private static ResourceDeclaration declaration(String code, String path) {
    ResourceDeclaration item = new ResourceDeclaration();
    item.setCode(code);
    item.setPath(path);
    return item;
  }

  private static Resource stored(String id, String code, String path, String owner, String parent) {
    Resource resource = new Resource();
    resource.setId(id);
    resource.setCode(code);
    resource.setPath(path);
    resource.setPluginId(owner);
    resource.setParentId(parent);
    return resource;
  }
}
