package org.simplepoint.plugin.ai.skill.rest.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/** Locks the public Skill HTTP surface to its least-privilege action matrix. */
class AiSkillPermissionMatrixTest {

  private static final List<Class<?>> CONTROLLERS = List.of(
      AiSkillController.class,
      AiSkillDraftController.class,
      AiSkillPublishController.class,
      AiSkillRegistryController.class,
      AiSkillVersionDesignerController.class
  );

  @Test
  void everyPublicHandlerDeclaresMethodSecurity() {
    CONTROLLERS.stream()
        .flatMap(controller -> List.of(controller.getDeclaredMethods()).stream())
        .filter(AiSkillPermissionMatrixTest::isHandler)
        .forEach(method -> assertThat(method.getAnnotation(PreAuthorize.class))
            .as("%s#%s", method.getDeclaringClass().getSimpleName(), method.getName())
            .isNotNull());
  }

  @Test
  void designerOperationsUseDedicatedLeastPrivilegeAuthorities() {
    assertAuthorities(AiSkillDraftController.class, Map.ofEntries(
        Map.entry("find", "ai.workbench.skills.drafts.view"),
        Map.entry("revisions", "ai.workbench.skills.drafts.view"),
        Map.entry("save", "ai.workbench.skills.drafts.manage"),
        Map.entry("remove", "ai.workbench.skills.drafts.manage"),
        Map.entry("restore", "ai.workbench.skills.drafts.manage"),
        Map.entry("validate", "ai.workbench.skills.drafts.manage"),
        Map.entry("compile", "ai.workbench.skills.drafts.manage"),
        Map.entry("startDebugExecution", "ai.workbench.skills.debug"),
        Map.entry("startMockTestRun", "ai.workbench.skills.debug"),
        Map.entry("pauseDebugExecution", "ai.workbench.skills.debug"),
        Map.entry("continueDebugExecution", "ai.workbench.skills.debug"),
        Map.entry("cancelDebugExecution", "ai.workbench.skills.debug"),
        Map.entry("setDebugBreakpoints", "ai.workbench.skills.debug")
    ));
    assertAuthorities(AiSkillPublishController.class, Map.of(
        "start", "ai.workbench.skills.publish",
        "findAll", "ai.workbench.skills.publish",
        "find", "ai.workbench.skills.publish",
        "retry", "ai.workbench.skills.publish"
    ));
    assertAuthorities(AiSkillRegistryController.class, Map.of(
        "describe", "ai.workbench.skills.publish",
        "checkConnectivity", "ai.workbench.skills.publish"
    ));
    assertAuthorities(AiSkillVersionDesignerController.class, Map.of(
        "find", "ai.workbench.skills.drafts.view",
        "copyToDraft", "ai.workbench.skills.drafts.manage"
    ));
  }

  private static void assertAuthorities(
      final Class<?> controller,
      final Map<String, String> expected
  ) {
    expected.forEach((methodName, authority) -> {
      Method method = List.of(controller.getDeclaredMethods()).stream()
          .filter(candidate -> candidate.getName().equals(methodName))
          .findFirst()
          .orElseThrow();
      assertThat(method.getAnnotation(PreAuthorize.class).value())
          .as("%s#%s", controller.getSimpleName(), methodName)
          .contains(authority);
    });
  }

  private static boolean isHandler(final Method method) {
    return method.isAnnotationPresent(GetMapping.class)
        || method.isAnnotationPresent(PostMapping.class)
        || method.isAnnotationPresent(PutMapping.class)
        || method.isAnnotationPresent(PatchMapping.class)
        || method.isAnnotationPresent(DeleteMapping.class);
  }
}
