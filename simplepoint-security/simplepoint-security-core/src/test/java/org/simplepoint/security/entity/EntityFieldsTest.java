package org.simplepoint.security.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.simplepoint.security.ResourceDeclaration;
import org.simplepoint.security.cache.AuthorityRecord;

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
  void resourceDeclaration_containsOnlyDeclarativeState() throws Exception {
    ResourceDeclaration declaration = new ResourceDeclaration();
    declaration.setName("Users");
    declaration.setCode("users.view");
    declaration.setDescription("User resource");
    declaration.setType(ResourceType.PAGE);

    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var json = mapper.readTree(mapper.writeValueAsBytes(declaration));
    assertThat(json.get("name").asText()).isEqualTo("Users");
    assertThat(json.get("description").asText()).isEqualTo("User resource");
    assertThat(json.get("code").asText()).isEqualTo("users.view");
    assertThat(json.get("type").asText()).isEqualTo("PAGE");
    assertThat(json.has("id")).isFalse();
    assertThat(json.has("createdAt")).isFalse();
    assertThat(json.has("deletedAt")).isFalse();
    assertThat(Resource.class.isAssignableFrom(ResourceDeclaration.class)).isFalse();
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
  void resourceNode_hasChildrenList() {
    ResourceNode tree = new ResourceNode();
    assertThat(tree.getChildren()).isNotNull().isEmpty();
    ResourceNode child = new ResourceNode();
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
  void roleResourceGrant_setterGetter() {
    RoleResourceGrant rel = new RoleResourceGrant();
    rel.setRoleId("role1");
    rel.setResourceCode("resources.view");
    rel.setDataScopeId("scope1");
    rel.setFieldScopeId("field1");
    assertThat(rel.getRoleId()).isEqualTo("role1");
    assertThat(rel.getResourceCode()).isEqualTo("resources.view");
    assertThat(rel.getDataScopeId()).isEqualTo("scope1");
    assertThat(rel.getFieldScopeId()).isEqualTo("field1");
  }

  @Test
  void resource_setterGetter() {
    Resource res = new Resource();
    res.setName("Users");
    res.setType(ResourceType.API);
    res.setParentId("root");
    res.setCode("resources.users");
    res.setDescription("User management resource");
    assertThat(res.getName()).isEqualTo("Users");
    assertThat(res.getType()).isEqualTo(ResourceType.API);
    assertThat(res.getParentId()).isEqualTo("root");
    assertThat(res.getCode()).isEqualTo("resources.users");
    assertThat(res.getDescription()).isEqualTo("User management resource");
  }

  @Test
  void microModule_setterGetter() {
    MicroModule module = new MicroModule();
    module.setDisplayName("My Module");
    assertThat(module.getDisplayName()).isEqualTo("My Module");
  }
}
