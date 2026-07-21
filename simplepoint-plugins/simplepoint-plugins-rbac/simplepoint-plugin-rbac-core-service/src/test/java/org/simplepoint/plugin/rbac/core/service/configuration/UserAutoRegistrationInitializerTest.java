package org.simplepoint.plugin.rbac.core.service.configuration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.plugin.rbac.core.service.properties.UserRegistrationProperties;
import org.simplepoint.security.entity.User;

class UserAutoRegistrationInitializerTest {

  @Test
  void existingBootstrapUserIsResolvedByEmailAndNotCreatedAgain() throws Exception {
    UsersService usersService = mock(UsersService.class);
    UserRegistrationProperties properties = new UserRegistrationProperties();
    User configured = new User();
    configured.setEmail("admin@example.com");
    configured.setPassword("secret");
    properties.setUsers(Set.of(configured));
    User existing = new User();
    existing.setId("user-1");
    existing.setEmail(configured.getEmail());
    when(usersService.loadUserByUsername(configured.getEmail())).thenReturn(existing);

    var contribution = new UserAutoRegistrationInitializer()
        .userRegistrationBootstrapContribution(usersService, properties)
        .contribution();
    contribution.action().run();

    verify(usersService).loadUserByUsername(configured.getEmail());
    verify(usersService, never()).create(configured);
  }

  @Test
  void missingBootstrapUserIsCreated() throws Exception {
    UsersService usersService = mock(UsersService.class);
    UserRegistrationProperties properties = new UserRegistrationProperties();
    User configured = new User();
    configured.setEmail("member@example.com");
    configured.setPassword("secret");
    properties.setUsers(Set.of(configured));
    when(usersService.loadUserByUsername(configured.getEmail())).thenThrow(new IllegalStateException("missing"));

    var contribution = new UserAutoRegistrationInitializer()
        .userRegistrationBootstrapContribution(usersService, properties)
        .contribution();
    contribution.action().run();

    verify(usersService).create(configured);
  }
}
