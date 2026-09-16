package org.simplepoint.plugin.ai.agent.rest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.agent.api.service.AiAgentExecutionService;
import org.simplepoint.plugin.ai.agent.api.service.AiAgentMemoryService;
import org.simplepoint.plugin.ai.agent.api.service.AiAgentService;
import org.simplepoint.plugin.ai.core.api.model.AiDependencyKind;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Exercises Agent dependency authorization through method security. */
class AiAgentMethodSecurityTest {

  private AnnotationConfigApplicationContext applicationContext;

  private AiAgentController controller;

  private AiAgentService agentService;

  private AiAgentExecutionService executionService;

  private AiAgentMemoryService memoryService;

  @BeforeEach
  void prepareSecurityContext() {
    SecurityContextHolder.clearContext();
    applicationContext = new AnnotationConfigApplicationContext(
        MethodSecurityConfiguration.class
    );
    controller = applicationContext.getBean(AiAgentController.class);
    agentService = applicationContext.getBean(AiAgentService.class);
    executionService = applicationContext.getBean(
        AiAgentExecutionService.class
    );
    memoryService = applicationContext.getBean(AiAgentMemoryService.class);
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
    applicationContext.close();
  }

  @Test
  void viewOnlySubjectCannotReadDependencyDirectories() {
    authenticate("ai.workbench.agents.view");

    assertThat(AopUtils.isAopProxy(controller)).isTrue();
    assertThatThrownBy(() -> controller.dependencyOptions(
        "agent-a",
        "MODEL",
        "",
        0,
        20
    )).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> controller.resolveDependencyOptions(
        "agent-a",
        "SKILL",
        "skill-version-a"
    )).isInstanceOf(AccessDeniedException.class);

    verifyNoInteractions(agentService, executionService, memoryService);
  }

  @Test
  void versionManagerCanReadDependencyDirectoriesWithoutViewAuthority() {
    authenticate("ai.workbench.agents.versions.manage");

    controller.dependencyOptions(
        "agent-a",
        "MODEL",
        "chat",
        1,
        20
    );
    controller.resolveDependencyOptions(
        "agent-a",
        "SKILL",
        "skill-version-a"
    );

    verify(agentService).findDependencyOptions(
        "agent-a",
        AiDependencyKind.MODEL,
        "chat",
        1,
        20
    );
    verify(agentService).resolveDependencyOptions(
        "agent-a",
        AiDependencyKind.SKILL,
        List.of("skill-version-a")
    );
    verifyNoInteractions(executionService, memoryService);
  }

  private static void authenticate(final String authority) {
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(
            "subject",
            "ignored",
            List.of(new SimpleGrantedAuthority(authority))
        )
    );
  }

  @Configuration(proxyBeanMethods = false)
  @EnableMethodSecurity
  static class MethodSecurityConfiguration {

    @Bean
    AiAgentService agentService() {
      return mock(AiAgentService.class);
    }

    @Bean
    AiAgentExecutionService executionService() {
      return mock(AiAgentExecutionService.class);
    }

    @Bean
    AiAgentMemoryService memoryService() {
      return mock(AiAgentMemoryService.class);
    }

    @Bean
    AiAgentController agentController(
        final AiAgentService agentService,
        final AiAgentExecutionService executionService,
        final AiAgentMemoryService memoryService
    ) {
      return new AiAgentController(
          agentService,
          executionService,
          memoryService
      );
    }
  }
}
