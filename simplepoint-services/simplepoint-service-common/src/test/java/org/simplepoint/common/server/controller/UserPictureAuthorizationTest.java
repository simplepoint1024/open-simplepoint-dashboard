package org.simplepoint.common.server.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.rbac.core.api.pojo.command.UserPictureUpdateCommand;
import org.simplepoint.plugin.rbac.core.api.service.UsersService;
import org.simplepoint.plugin.rbac.core.rest.controller.UsersController;
import org.simplepoint.security.entity.User;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Exercises the actual Spring method-security proxy, not only annotation presence. */
class UserPictureAuthorizationTest {
  @AfterEach void cleanup() {SecurityContextHolder.clearContext();}

  @Test void anonymousCannotWriteButAuthenticatedAccountsCanWriteOnlyThemselves() {
    try (var context = new AnnotationConfigApplicationContext(SecurityConfiguration.class)) {
      var controller = context.getBean(UsersController.class);
      var service = context.getBean(UsersService.class);
      SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("test", "anonymous",
          List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
      assertThrows(AccessDeniedException.class, () -> controller.updateCurrentPicture(new UserPictureUpdateCommand(null)));
      verifyNoInteractions(service);
      var user = new User(); user.setId("current-account"); user.setName("display-name-is-not-the-target");
      SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
      when(service.updateCurrentPicture(user.getId(), "/common/object-storage/images/new")).thenReturn(user);
      controller.updateCurrentPicture(new UserPictureUpdateCommand("/common/object-storage/images/new"));
      verify(service).updateCurrentPicture("current-account", "/common/object-storage/images/new");
    }
  }
  @Test void pickerRequiresItsRealIndependentCapabilityAndDeniesAnonymousBeforeTheService() {
    try (var context = new AnnotationConfigApplicationContext(SecurityConfiguration.class)) {
      var controller = context.getBean(UsersController.class);
      var service = context.getBean(UsersService.class);
      SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("test", "anonymous",
          List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
      assertThrows(AccessDeniedException.class, () -> controller.pickerItems("picker", org.springframework.data.domain.PageRequest.of(0, 20)));
      assertThrows(AccessDeniedException.class, () -> controller.selectedPickerItems("u1"));
      verifyNoInteractions(service);
      SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("reader", null,
          List.of(new SimpleGrantedAuthority("workflow.definitions.view"))));
      assertThrows(AccessDeniedException.class, () -> controller.selectedPickerItems("u1"));
      verifyNoInteractions(service);
      SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("owner-editor", null,
          List.of(new SimpleGrantedAuthority("tenants.edit"))));
      when(service.searchPickerItems(anyString(), any())).thenReturn(org.springframework.data.domain.Page.empty());
      when(service.resolvePickerItems(any())).thenReturn(List.of());
      controller.pickerItems("picker", org.springframework.data.domain.PageRequest.of(0, 20));
      controller.selectedPickerItems("u1");
      verify(service).searchPickerItems(eq("picker"), any());
      verify(service).resolvePickerItems(java.util.Set.of("u1"));
    }
  }

  @Configuration(proxyBeanMethods = false) @EnableMethodSecurity(proxyTargetClass = true)
  static class SecurityConfiguration {
    @Bean UsersService usersService() {return mock(UsersService.class);}
    @Bean UsersController usersController(UsersService service) {return new UsersController(service);}
  }
}
