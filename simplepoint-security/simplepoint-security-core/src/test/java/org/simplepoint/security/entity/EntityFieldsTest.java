package org.simplepoint.security.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.simplepoint.security.cache.AuthorityRecord;
import org.simplepoint.security.MenuFeatureDefinition;

class EntityFieldsTest {

  @Test
  void authorityRecord_fields() {
    AuthorityRecord record = new AuthorityRecord("id1", "ROLE_ADMIN");
    assertThat(record.id()).isEqualTo("id1");
    assertThat(record.authority()).isEqualTo("ROLE_ADMIN");
  }

  @Test
  void authorityRecord_equality() {
    AuthorityRecord r1 = new AuthorityRecord("id", "auth");
    AuthorityRecord r2 = new AuthorityRecord("id", "auth");
    assertThat(r1).isEqualTo(r2);
    assertThat(r1.hashCode()).isEqualTo(r2.hashCode());
  }

  @Test
  void menuFeatureDefinition_setterGetter() {
    MenuFeatureDefinition def = new MenuFeatureDefinition();
    def.setName("My Feature");
    def.setDescription("Feature description");
    def.setCode("feature.code");
    assertThat(def.getName()).isEqualTo("My Feature");
    assertThat(def.getDescription()).isEqualTo("Feature description");
    assertThat(def.getCode()).isEqualTo("feature.code");
  }

  @Test
  void permissions_constants() {
    assertThat(Permissions.ACCESS_TYPE).isEqualTo(0);
    assertThat(Permissions.OPERATION_TYPE).isEqualTo(1);
    assertThat(Permissions.AUTHORITY_FIELD).isEqualTo("authority");
  }

  @Test
  void permissions_setterGetter() {
    Permissions p = new Permissions();
    p.setResource("user.list");
    p.setType(Permissions.ACCESS_TYPE);
    assertThat(p.getResource()).isEqualTo("user.list");
    assertThat(p.getType()).isEqualTo(0);
  }

  @Test
  void role_setterGetter() {
    Role role = new Role();
    role.setRoleName("Admin");
    role.setAuthority("ROLE_ADMIN");
    assertThat(role.getRoleName()).isEqualTo("Admin");
    assertThat(role.getAuthority()).isEqualTo("ROLE_ADMIN");
  }

  @Test
  void treeMenu_hasChildrenList() {
    TreeMenu tree = new TreeMenu();
    assertThat(tree.getChildren()).isNotNull().isEmpty();
    Menu child = new Menu();
    child.setLabel("Child");
    tree.getChildren().add(child);
    assertThat(tree.getChildren()).hasSize(1);
  }

  @Test
  void userRoleRelevance_setterGetter() {
    UserRoleRelevance rel = new UserRoleRelevance();
    rel.setUserId("user1");
    rel.setRoleId("role1");
    assertThat(rel.getUserId()).isEqualTo("user1");
    assertThat(rel.getRoleId()).isEqualTo("role1");
  }

  @Test
  void rolePermissionsRelevance_setterGetter() {
    RolePermissionsRelevance rel = new RolePermissionsRelevance();
    rel.setRoleId("role1");
    rel.setPermissionAuthority("perm.read");
    rel.setDataScopeId("scope1");
    rel.setFieldScopeId("field1");
    assertThat(rel.getRoleId()).isEqualTo("role1");
    assertThat(rel.getPermissionAuthority()).isEqualTo("perm.read");
    assertThat(rel.getDataScopeId()).isEqualTo("scope1");
    assertThat(rel.getFieldScopeId()).isEqualTo("field1");
  }

  @Test
  void resource_setterGetter() {
    Resource res = new Resource();
    res.setResourceName("Users");
    res.setResourceType("API");
    res.setResourceParent("root");
    res.setResourceAuthority("resource.users");
    res.setResourceDescription("User management resource");
    assertThat(res.getResourceName()).isEqualTo("Users");
    assertThat(res.getResourceType()).isEqualTo("API");
    assertThat(res.getResourceParent()).isEqualTo("root");
    assertThat(res.getResourceAuthority()).isEqualTo("resource.users");
    assertThat(res.getResourceDescription()).isEqualTo("User management resource");
  }

  @Test
  void resourcesPermissionsRelevance_twoArgConstructor() {
    ResourcesPermissionsRelevance rel = new ResourcesPermissionsRelevance("perm.read", "resource1");
    assertThat(rel.getPermissionAuthority()).isEqualTo("perm.read");
    assertThat(rel.getResourceId()).isEqualTo("resource1");
  }

  @Test
  void resourcesPermissionsRelevance_defaultConstructor() {
    ResourcesPermissionsRelevance rel = new ResourcesPermissionsRelevance();
    assertThat(rel.getPermissionAuthority()).isNull();
    assertThat(rel.getResourceId()).isNull();
  }

  @Test
  void microModule_setterGetter() {
    MicroModule module = new MicroModule();
    module.setDisplayName("My Module");
    assertThat(module.getDisplayName()).isEqualTo("My Module");
  }
}
