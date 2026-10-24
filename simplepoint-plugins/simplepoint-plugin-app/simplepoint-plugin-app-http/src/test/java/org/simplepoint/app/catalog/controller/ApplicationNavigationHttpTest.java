package org.simplepoint.app.catalog.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import org.junit.jupiter.api.*;
import org.simplepoint.app.catalog.service.ApplicationNavigationService;
import org.simplepoint.app.catalog.service.ApplicationNavigationState;
import org.simplepoint.app.form.controller.ApplicationFormExceptionHandler;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Verifies real MVC argument binding without compiler parameter-name retention. */
class ApplicationNavigationHttpTest {
  private ApplicationNavigationService navigation;
  private MockMvc mvc;

  @BeforeEach void setup() {
    navigation = mock(ApplicationNavigationService.class);
    var proxy = new ProxyFactory(new ApplicationNavigationController(navigation));
    proxy.setProxyTargetClass(true);
    proxy.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
    mvc = MockMvcBuilders.standaloneSetup(proxy.getProxy())
        .setControllerAdvice(new ApplicationFormExceptionHandler()).build();
    authenticate("app.workbench.view", "app.workbench.forms.edit");
  }
  @AfterEach void cleanup() {SecurityContextHolder.clearContext();}

  @Test void bindsPathQueryAndJsonCommandsAndRejectsInvalidPaging() throws Exception {
    when(navigation.view("app-a")).thenReturn(new ApplicationNavigationState.View(0, false, null));
    when(navigation.directories("app-a", "业务", 100, 50))
        .thenReturn(new ApplicationNavigationService.DirectoryPage(List.of(), 0));
    mvc.perform(get("/applications/app-a/navigation")).andExpect(status().isOk());
    mvc.perform(get("/applications/app-a/navigation/directories")
        .param("keyword", "业务").param("page", "2").param("size", "50"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.number").value(2));
    verify(navigation).view("app-a");
    verify(navigation).directories("app-a", "业务", 100, 50);
    for (String path : List.of("", "/cancel")) {
      mvc.perform(post("/applications/app-a/navigation" + path)
          .contentType("application/json").content("{\"expectedRevision\":4,\"configuration\":null}"))
          .andExpect(status().isOk());
    }
    var command = new ApplicationNavigationState.Update(4, null);
    verify(navigation).update("app-a", command);
    verify(navigation).cancel("app-a", command);
    mvc.perform(get("/applications/app-a/navigation/directories").param("size", "501"))
        .andExpect(status().isBadRequest());
    verifyNoMoreInteractions(navigation);
  }

  @Test void viewerCannotDiscoverDirectoriesOrChangeConfiguration() {
    authenticate("app.workbench.view");
    assertThatThrownBy(() -> mvc.perform(get("/applications/app-a/navigation/directories")))
        .hasRootCauseInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(() -> mvc.perform(post("/applications/app-a/navigation")
        .contentType("application/json").content("{\"expectedRevision\":0,\"configuration\":null}")))
        .hasRootCauseInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    verifyNoInteractions(navigation);
  }
  private void authenticate(String... authorities) {
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
        "user", "unused", java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
  }
}
