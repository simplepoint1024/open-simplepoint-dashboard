package org.simplepoint.plugin.rbac.core.service.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.core.api.service.UserManagementService;
import org.simplepoint.plugin.rbac.core.service.properties.UserRegistrationProperties;
import org.simplepoint.security.entity.User;

class UserAutoRegistrationInitializerTest {

  @Test
  void existingBootstrapUserIsResolvedByEmailAndNotCreatedAgain() throws Exception {
    UserRegistrationProperties properties = new UserRegistrationProperties();
    User configured = new User();
    configured.setEmail("admin@example.com");
    configured.setPassword("secret");
    properties.setUsers(Set.of(configured));
    User existing = new User();
    existing.setId("user-1");
    existing.setEmail(configured.getEmail());
    UserManagementService usersService = mock(UserManagementService.class);
    SystemUserBootstrap bootstrap = mock(SystemUserBootstrap.class);
    when(usersService.loadUserByPhoneOrEmail(configured.getEmail())).thenReturn(existing);

    var contribution = new UserAutoRegistrationInitializer()
        .userRegistrationBootstrapContribution(usersService, bootstrap, properties)
        .contribution();
    assertThat(contribution.version()).isEqualTo("3");
    contribution.action().run();

    verify(usersService).loadUserByPhoneOrEmail(configured.getEmail());
    verify(usersService, never()).create(configured);
    verify(bootstrap, never()).createBootstrapAccount(configured);
  }

  @Test
  void missingBootstrapUserIsCreated() throws Exception {
    UserRegistrationProperties properties = new UserRegistrationProperties();
    User configured = new User();
    configured.setEmail("member@example.com");
    configured.setPassword("secret");
    properties.setUsers(Set.of(configured));
    UserManagementService usersService = mock(UserManagementService.class);
    SystemUserBootstrap bootstrap = mock(SystemUserBootstrap.class);

    var contribution = new UserAutoRegistrationInitializer()
        .userRegistrationBootstrapContribution(usersService, bootstrap, properties)
        .contribution();
    contribution.action().run();

    verify(bootstrap).createBootstrapAccount(configured);
    verify(usersService, never()).create(configured);
  }

  @Test
  void failedBootstrapLookupDoesNotAttemptAccountCreation() throws Exception {
    var properties = new UserRegistrationProperties();
    var configured = new User();
    configured.setEmail("root@example.test");
    properties.setUsers(Set.of(configured));
    var users = mock(UserManagementService.class);
    var bootstrap = mock(SystemUserBootstrap.class);
    when(users.loadUserByPhoneOrEmail(configured.getEmail())).thenThrow(new IllegalStateException("database unavailable"));
    var contribution = new UserAutoRegistrationInitializer().userRegistrationBootstrapContribution(users, bootstrap,
        properties).contribution();
    assertThatThrownBy(() -> contribution.action().run()).hasMessageContaining("database unavailable");
    verify(bootstrap, never()).createBootstrapAccount(configured);
  }
}
