package org.simplepoint.plugin.ai.workflow.rest.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/** Locks the public Workflow HTTP surface to its action permission contract. */
class AiWorkflowPermissionMatrixTest {

  private static final String VIEW_AUTHORITY =
      "ai.workbench.workflows.view";

  private static final Pattern WORKFLOW_AUTHORITY_PATTERN = Pattern.compile(
      "ai\\.workbench\\.workflows\\.[a-z-]+(?:\\.[a-z-]+)*"
  );

  private static final Map<String, String> EXPECTED_PERMISSIONS =
      Map.ofEntries(
          Map.entry("view", VIEW_AUTHORITY),
          Map.entry("create", "ai.workbench.workflows.create"),
          Map.entry("edit", "ai.workbench.workflows.edit"),
          Map.entry("delete", "ai.workbench.workflows.delete"),
          Map.entry(
              "manageVersions",
              "ai.workbench.workflows.versions.manage"
          ),
          Map.entry("publish", "ai.workbench.workflows.publish"),
          Map.entry("execute", "ai.workbench.workflows.execute"),
          Map.entry("intervene", "ai.workbench.workflows.intervene")
      );

  private static final Map<String, String> HANDLER_AUTHORITIES =
      Map.ofEntries(
          Map.entry("findAll", VIEW_AUTHORITY),
          Map.entry("find", VIEW_AUTHORITY),
          Map.entry("create", "ai.workbench.workflows.create"),
          Map.entry("update", "ai.workbench.workflows.edit"),
          Map.entry("remove", "ai.workbench.workflows.delete"),
          Map.entry("versions", VIEW_AUTHORITY),
          Map.entry("version", VIEW_AUTHORITY),
          Map.entry(
              "dependencyOptions",
              "ai.workbench.workflows.versions.manage"
          ),
          Map.entry(
              "resolveDependencyOptions",
              "ai.workbench.workflows.versions.manage"
          ),
          Map.entry(
              "createVersion",
              "ai.workbench.workflows.versions.manage"
          ),
          Map.entry("publish", "ai.workbench.workflows.publish"),
          Map.entry("deprecate", "ai.workbench.workflows.publish"),
          Map.entry("execute", "ai.workbench.workflows.execute"),
          Map.entry("executions", VIEW_AUTHORITY),
          Map.entry("execution", VIEW_AUTHORITY),
          Map.entry("executionEvents", VIEW_AUTHORITY),
          Map.entry("pauseExecution", "ai.workbench.workflows.intervene"),
          Map.entry("resumeExecution", "ai.workbench.workflows.intervene"),
          Map.entry("cancelExecution", "ai.workbench.workflows.intervene"),
          Map.entry("respondHumanTask", "ai.workbench.workflows.intervene")
      );

  @Test
  void eachAuthorityOnlyEnablesItsMappedPermission() {
    assertThat(AiWorkflowController.WORKBENCH_PERMISSION_AUTHORITIES)
        .containsExactlyInAnyOrderEntriesOf(EXPECTED_PERMISSIONS);

    EXPECTED_PERMISSIONS.forEach((expectedField, authority) -> {
      Map<String, Boolean> snapshot =
          AiWorkflowController.resolveWorkbenchPermissions(
              authentication(authority)
          );

      assertThat(snapshot).hasSize(EXPECTED_PERMISSIONS.size());
      snapshot.forEach((field, granted) -> assertThat(granted)
          .as("%s from %s", field, authority)
          .isEqualTo(field.equals(expectedField)));
    });
  }

  @Test
  void viewOnlyDoesNotImplyMutationPermissions() {
    Map<String, Boolean> snapshot =
        AiWorkflowController.resolveWorkbenchPermissions(
            authentication(VIEW_AUTHORITY)
        );

    assertThat(snapshot).containsEntry("view", true);
    assertThat(snapshot.entrySet())
        .filteredOn(entry -> !"view".equals(entry.getKey()))
        .allSatisfy(entry -> assertThat(entry.getValue()).isFalse());
  }

  @Test
  void multipleAuthoritiesOnlyEnableTheirMappedPermissions() {
    Map<String, Boolean> snapshot =
        AiWorkflowController.resolveWorkbenchPermissions(authentication(
            "ai.workbench.workflows.edit",
            "ai.workbench.workflows.intervene",
            "unrelated.authority"
        ));

    assertThat(snapshot)
        .containsEntry("edit", true)
        .containsEntry("intervene", true)
        .containsEntry("view", false)
        .containsEntry("create", false)
        .containsEntry("execute", false);
  }

  @Test
  void administratorReceivesEveryPermission() {
    Map<String, Boolean> snapshot =
        AiWorkflowController.resolveWorkbenchPermissions(
            authentication("ROLE_Administrator")
        );

    assertThat(snapshot)
        .hasSize(EXPECTED_PERMISSIONS.size())
        .allSatisfy((field, granted) -> assertThat(granted)
            .as(field)
            .isTrue());
  }

  @Test
  void missingOrUntrustedAuthenticationFailsClosed() {
    assertAllDenied(AiWorkflowController.resolveWorkbenchPermissions(null));
    assertAllDenied(AiWorkflowController.resolveWorkbenchPermissions(
        authentication()
    ));

    Authentication unauthenticated = mock(Authentication.class);
    when(unauthenticated.isAuthenticated()).thenReturn(false);
    assertAllDenied(AiWorkflowController.resolveWorkbenchPermissions(
        unauthenticated
    ));
    verify(unauthenticated, never()).getAuthorities();

    Authentication nullAuthorities = mock(Authentication.class);
    when(nullAuthorities.isAuthenticated()).thenReturn(true);
    when(nullAuthorities.getAuthorities()).thenReturn(null);
    assertAllDenied(AiWorkflowController.resolveWorkbenchPermissions(
        nullAuthorities
    ));

    Authentication malformedAuthorities = mock(Authentication.class);
    when(malformedAuthorities.isAuthenticated()).thenReturn(true);
    doReturn(Arrays.asList(
        null,
        mock(GrantedAuthorityWithNullValue.class)
    )).when(malformedAuthorities).getAuthorities();
    assertAllDenied(AiWorkflowController.resolveWorkbenchPermissions(
        malformedAuthorities
    ));
  }

  @Test
  void readsOneAuthenticationAuthoritySnapshot() {
    Authentication authentication = mock(Authentication.class);
    when(authentication.isAuthenticated()).thenReturn(true);
    doReturn(List.of(
        new SimpleGrantedAuthority("ai.workbench.workflows.intervene")
    )).when(authentication).getAuthorities();

    Map<String, Boolean> snapshot =
        AiWorkflowController.resolveWorkbenchPermissions(authentication);

    assertThat(snapshot)
        .containsEntry("intervene", true)
        .containsEntry("view", false);
    verify(authentication, times(1)).getAuthorities();
  }

  @Test
  void everyHandlerUsesItsLeastPrivilegeWorkflowAuthority() {
    List<Method> handlers = Arrays.stream(
            AiWorkflowController.class.getDeclaredMethods()
        )
        .filter(AiWorkflowPermissionMatrixTest::isHandler)
        .toList();
    assertThat(handlers)
        .allSatisfy(method -> assertThat(
            method.getAnnotation(PreAuthorize.class)
        ).as(method.getName()).isNotNull());
    assertThat(HANDLER_AUTHORITIES.keySet())
        .containsExactlyInAnyOrderElementsOf(handlers.stream()
            .map(Method::getName)
            .filter(name -> !"workbenchPermissions".equals(name))
            .toList());

    HANDLER_AUTHORITIES.forEach((methodName, authority) ->
        assertWorkflowAuthorities(methodName, Set.of(authority)));
    assertWorkflowAuthorities(
        "workbenchPermissions",
        Set.copyOf(EXPECTED_PERMISSIONS.values())
    );
  }

  @Test
  void seededWorkflowAuthoritiesMatchTheControllerSnapshot()
      throws IOException {
    JsonNode root = new ObjectMapper().readTree(
        workflowResourceSeed().toFile()
    );
    JsonNode workflowResource = findResource(root, VIEW_AUTHORITY);

    assertThat(workflowResource).isNotNull();
    Set<String> seededAuthorities = StreamSupport.stream(
            workflowResource.path("children").spliterator(),
            false
        )
        .map(child -> child.path("code").asText())
        .collect(Collectors.toSet());
    seededAuthorities.add(workflowResource.path("code").asText());

    assertThat(seededAuthorities)
        .containsExactlyInAnyOrderElementsOf(EXPECTED_PERMISSIONS.values());
  }

  private static void assertWorkflowAuthorities(
      final String methodName,
      final Set<String> expected
  ) {
    Method method = Arrays.stream(
            AiWorkflowController.class.getDeclaredMethods()
        )
        .filter(candidate -> candidate.getName().equals(methodName))
        .findFirst()
        .orElseThrow();
    String expression = method.getAnnotation(PreAuthorize.class).value();
    Set<String> actual = WORKFLOW_AUTHORITY_PATTERN.matcher(expression)
        .results()
        .map(result -> result.group())
        .collect(Collectors.toSet());
    assertThat(actual).as(methodName)
        .containsExactlyInAnyOrderElementsOf(expected);
  }

  private static void assertAllDenied(
      final Map<String, Boolean> snapshot
  ) {
    assertThat(snapshot)
        .hasSize(EXPECTED_PERMISSIONS.size())
        .allSatisfy((field, granted) -> assertThat(granted)
            .as(field)
            .isFalse());
  }

  private static Authentication authentication(
      final String... authorities
  ) {
    return new UsernamePasswordAuthenticationToken(
        "subject",
        "ignored",
        Arrays.stream(authorities)
            .map(SimpleGrantedAuthority::new)
            .toList()
    );
  }

  private static boolean isHandler(final Method method) {
    return method.isAnnotationPresent(GetMapping.class)
        || method.isAnnotationPresent(PostMapping.class)
        || method.isAnnotationPresent(PutMapping.class)
        || method.isAnnotationPresent(DeleteMapping.class);
  }

  private static Path workflowResourceSeed() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      Path candidate = current.resolve(
          "simplepoint-services/simplepoint-service-ai/src/main/resources/"
              + "META-INF/simplepoint/resources/ai.json"
      );
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Cannot locate the AI resource seed");
  }

  private static JsonNode findResource(
      final JsonNode node,
      final String code
  ) {
    if (node.isObject() && code.equals(node.path("code").asText())) {
      return node;
    }
    for (JsonNode child : node) {
      JsonNode match = findResource(child, code);
      if (match != null) {
        return match;
      }
    }
    return null;
  }

  private interface GrantedAuthorityWithNullValue
      extends org.springframework.security.core.GrantedAuthority {
  }
}
