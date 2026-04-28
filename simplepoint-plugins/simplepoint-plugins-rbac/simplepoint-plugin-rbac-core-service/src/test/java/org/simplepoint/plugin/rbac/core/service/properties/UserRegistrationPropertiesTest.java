package org.simplepoint.plugin.rbac.core.service.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.security.entity.User;

class UserRegistrationPropertiesTest {

  @Test
  void getUsers_returnsUnmodifiableView() {
    UserRegistrationProperties props = new UserRegistrationProperties();
    Set<User> view = props.getUsers();
    assertThat(view).isEmpty();
    assertThatThrownBy(() -> view.add(new User()))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void setUsers_addsAllToInternalSet() {
    UserRegistrationProperties props = new UserRegistrationProperties();
    User u1 = new User();
    u1.setId("id-admin");
    u1.setUsername("admin");
    User u2 = new User();
    u2.setId("id-user");
    u2.setUsername("user");
    props.setUsers(java.util.Arrays.stream(new User[]{u1, u2})
        .collect(java.util.stream.Collectors.toSet()));
    assertThat(props.getUsers()).hasSize(2);
  }

  @Test
  void setUsers_calledTwice_accumulatesUsers() {
    UserRegistrationProperties props = new UserRegistrationProperties();
    User u1 = new User();
    u1.setId("id-admin");
    u1.setUsername("admin");
    User u2 = new User();
    u2.setId("id-user");
    u2.setUsername("user");
    props.setUsers(java.util.Collections.singleton(u1));
    props.setUsers(java.util.Collections.singleton(u2));
    assertThat(props.getUsers()).hasSize(2);
  }

  @Test
  void setUsers_withEmptySet_leavesExistingUsers() {
    UserRegistrationProperties props = new UserRegistrationProperties();
    User u1 = new User();
    u1.setId("id-admin");
    u1.setUsername("admin");
    props.setUsers(java.util.Collections.singleton(u1));
    props.setUsers(Set.of());
    assertThat(props.getUsers()).hasSize(1);
  }
}
