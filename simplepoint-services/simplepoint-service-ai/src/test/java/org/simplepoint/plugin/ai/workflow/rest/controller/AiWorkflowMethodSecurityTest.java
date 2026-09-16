package org.simplepoint.plugin.ai.workflow.rest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.ai.core.api.model.AiDependencyKind;
import org.simplepoint.plugin.ai.workflow.api.service.AiWorkflowExecutionService;
import org.simplepoint.plugin.ai.workflow.api.service.AiWorkflowService;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Exercises Workflow authorization through a real Spring method-security proxy. */
class AiWorkflowMethodSecurityTest {

  private static final String VIEW_AUTHORITY =
      "ai.workbench.workflows.view";

  private static final String CREATE_AUTHORITY =
      "ai.workbench.workflows.create";

  private AnnotationConfigApplicationContext applicationContext;

  private AiWorkflowController controller;

  private AiWorkflowService workflowService;

  private AiWorkflowExecutionService executionService;

  @BeforeEach
  void prepareSecurityContext() {
    SecurityContextHolder.clearContext();
    applicationContext = new AnnotationConfigApplicationContext(
        MethodSecurityConfiguration.class
    );
    controller = applicationContext.getBean(AiWorkflowController.class);
    workflowService = applicationContext.getBean(AiWorkflowService.class);
    executionService = applicationContext.getBean(
        AiWorkflowExecutionService.class
    );
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
    applicationContext.close();
  }

  @Test
  void viewOnlySubjectIsDeniedBeforeEveryMutationReachesItsService() {
    SecurityContextHolder.getContext().setAuthentication(
        authentication(VIEW_AUTHORITY)
    );

    List<SecuredMutation> mutations = List.of(
        mutation("create", () -> controller.create(null)),
        mutation("update", () -> controller.update("workflow-a", null)),
        mutation("remove", () -> controller.remove("workflow-a")),
        mutation(
            "createVersion",
            () -> controller.createVersion("workflow-a", null)
        ),
        mutation(
            "dependencyOptions",
            () -> controller.dependencyOptions(
                "workflow-a",
                "AGENT",
                "",
                0,
                20
            )
        ),
        mutation(
            "resolveDependencyOptions",
            () -> controller.resolveDependencyOptions(
                "workflow-a",
                "AGENT",
                "version-a"
            )
        ),
        mutation(
            "publish",
            () -> controller.publish("workflow-a", "version-a")
        ),
        mutation(
            "deprecate",
            () -> controller.deprecate("workflow-a", "version-a")
        ),
        mutation("execute", () -> controller.execute("workflow-a", null)),
        mutation(
            "pauseExecution",
            () -> controller.pauseExecution(
                "workflow-a",
                "execution-a",
                null
            )
        ),
        mutation(
            "resumeExecution",
            () -> controller.resumeExecution("workflow-a", "execution-a")
        ),
        mutation(
            "cancelExecution",
            () -> controller.cancelExecution("workflow-a", "execution-a")
        ),
        mutation(
            "respondHumanTask",
            () -> controller.respondHumanTask(
                "workflow-a",
                "execution-a",
                "task-a",
                null
            )
        )
    );

    assertThat(mutations).hasSize(13);
    mutations.forEach(mutation -> assertThatThrownBy(mutation.invocation())
        .as(mutation.name())
        .isInstanceOf(AccessDeniedException.class));
    verifyNoInteractions(workflowService, executionService);
  }

  @Test
  void versionManagerCanReadDependencyDirectoriesWithoutViewAuthority() {
    SecurityContextHolder.getContext().setAuthentication(authentication(
        "ai.workbench.workflows.versions.manage"
    ));

    controller.dependencyOptions(
        "workflow-a",
        "AGENT",
        "agent",
        1,
        20
    );
    controller.resolveDependencyOptions(
        "workflow-a",
        "SKILL",
        "skill-version-a"
    );

    verify(workflowService).findDependencyOptions(
        "workflow-a",
        AiDependencyKind.AGENT,
        "agent",
        1,
        20
    );
    verify(workflowService).resolveDependencyOptions(
        "workflow-a",
        AiDependencyKind.SKILL,
        List.of("skill-version-a")
    );
    verifyNoInteractions(executionService);
  }

  @Test
  void mutationOnlySubjectCanReadItsOwnFailClosedPermissionSnapshot() {
    Authentication authentication = authentication(CREATE_AUTHORITY);
    SecurityContextHolder.getContext().setAuthentication(authentication);

    assertThat(AopUtils.isAopProxy(controller)).isTrue();
    Response<?> response = controller.workbenchPermissions(authentication);

    assertThat(response.getBody()).isInstanceOf(Map.class);
    assertThat(castPermissionSnapshot(response.getBody()))
        .hasSize(8)
        .containsEntry("create", true)
        .containsEntry("view", false)
        .containsEntry("edit", false)
        .containsEntry("delete", false)
        .containsEntry("manageVersions", false)
        .containsEntry("publish", false)
        .containsEntry("execute", false)
        .containsEntry("intervene", false);
    verifyNoInteractions(workflowService, executionService);
  }

  private static SecuredMutation mutation(
      final String name,
      final ThrowingCallable invocation
  ) {
    return new SecuredMutation(name, invocation);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Boolean> castPermissionSnapshot(
      final Object body
  ) {
    return (Map<String, Boolean>) body;
  }

  private static Authentication authentication(final String authority) {
    return new UsernamePasswordAuthenticationToken(
        "subject",
        "ignored",
        List.of(new SimpleGrantedAuthority(authority))
    );
  }

  private record SecuredMutation(
      String name,
      ThrowingCallable invocation
  ) {
  }

  @Configuration(proxyBeanMethods = false)
  @EnableMethodSecurity
  static class MethodSecurityConfiguration {

    @Bean
    AiWorkflowService workflowService() {
      return mock(AiWorkflowService.class);
    }

    @Bean
    AiWorkflowExecutionService executionService() {
      return mock(AiWorkflowExecutionService.class);
    }

    @Bean
    AiWorkflowController workflowController(
        final AiWorkflowService workflowService,
        final AiWorkflowExecutionService executionService
    ) {
      return new AiWorkflowController(workflowService, executionService);
    }
  }
}
