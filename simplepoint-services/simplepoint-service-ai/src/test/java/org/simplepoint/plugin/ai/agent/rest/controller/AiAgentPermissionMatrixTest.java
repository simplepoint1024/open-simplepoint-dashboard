package org.simplepoint.plugin.ai.agent.rest.controller;

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

/** Locks the public Agent HTTP surface to its action permission contract. */
class AiAgentPermissionMatrixTest {

  private static final String VIEW_AUTHORITY =
      "ai.workbench.agents.view";

  private static final Pattern AGENT_AUTHORITY_PATTERN = Pattern.compile(
      "ai\\.workbench\\.agents\\.[a-z-]+(?:\\.[a-z-]+)*"
  );

  private static final Map<String, String> EXPECTED_PERMISSIONS =
      Map.ofEntries(
          Map.entry("view", VIEW_AUTHORITY),
          Map.entry("create", "ai.workbench.agents.create"),
          Map.entry("edit", "ai.workbench.agents.edit"),
          Map.entry("delete", "ai.workbench.agents.delete"),
          Map.entry(
              "manageVersions",
              "ai.workbench.agents.versions.manage"
          ),
          Map.entry("publish", "ai.workbench.agents.publish"),
          Map.entry("execute", "ai.workbench.agents.execute"),
          Map.entry("approve", "ai.workbench.agents.approve"),
          Map.entry("control", "ai.workbench.agents.control"),
          Map.entry(
              "manageMemory",
              "ai.workbench.agents.memory.manage"
          ),
          Map.entry("intervene", "ai.workbench.agents.intervene")
      );

  private static final Map<String, String> HANDLER_AUTHORITIES =
      Map.ofEntries(
          Map.entry("findAll", VIEW_AUTHORITY),
          Map.entry("find", VIEW_AUTHORITY),
          Map.entry("create", "ai.workbench.agents.create"),
          Map.entry("update", "ai.workbench.agents.edit"),
          Map.entry("remove", "ai.workbench.agents.delete"),
          Map.entry("versions", VIEW_AUTHORITY),
          Map.entry("version", VIEW_AUTHORITY),
          Map.entry(
              "dependencyOptions",
              "ai.workbench.agents.versions.manage"
          ),
          Map.entry(
              "resolveDependencyOptions",
              "ai.workbench.agents.versions.manage"
          ),
          Map.entry(
              "createVersion",
              "ai.workbench.agents.versions.manage"
          ),
          Map.entry("publish", "ai.workbench.agents.publish"),
          Map.entry("deprecate", "ai.workbench.agents.publish"),
          Map.entry("execute", "ai.workbench.agents.execute"),
          Map.entry("executions", VIEW_AUTHORITY),
          Map.entry("execution", VIEW_AUTHORITY),
          Map.entry("traces", VIEW_AUTHORITY),
          Map.entry("events", VIEW_AUTHORITY),
          Map.entry("metrics", VIEW_AUTHORITY),
          Map.entry("memories", VIEW_AUTHORITY),
          Map.entry(
              "removeMemory",
              "ai.workbench.agents.memory.manage"
          ),
          Map.entry("approveExecution", "ai.workbench.agents.approve"),
          Map.entry("rejectExecution", "ai.workbench.agents.approve"),
          Map.entry("pauseExecution", "ai.workbench.agents.control"),
          Map.entry("resumeExecution", "ai.workbench.agents.control"),
          Map.entry(
              "requestHumanIntervention",
              "ai.workbench.agents.intervene"
          ),
          Map.entry(
              "respondHumanIntervention",
              "ai.workbench.agents.intervene"
          ),
          Map.entry("cancelExecution", "ai.workbench.agents.execute")
      );

  @Test
  void eachAuthorityOnlyEnablesItsMappedPermission() {
    assertThat(AiAgentController.WORKBENCH_PERMISSION_AUTHORITIES)
        .containsExactlyInAnyOrderEntriesOf(EXPECTED_PERMISSIONS);

    EXPECTED_PERMISSIONS.forEach((expectedField, authority) -> {
      Map<String, Boolean> snapshot =
          AiAgentController.resolveWorkbenchPermissions(
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
        AiAgentController.resolveWorkbenchPermissions(
            authentication(VIEW_AUTHORITY)
        );

    assertThat(snapshot).containsEntry("view", true);
    assertThat(snapshot.entrySet())
        .filteredOn(entry -> !"view".equals(entry.getKey()))
        .allSatisfy(entry -> assertThat(entry.getValue()).isFalse());
  }

  @Test
  void administratorReceivesEveryPermission() {
    Map<String, Boolean> snapshot =
        AiAgentController.resolveWorkbenchPermissions(
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
    assertAllDenied(AiAgentController.resolveWorkbenchPermissions(null));
    assertAllDenied(AiAgentController.resolveWorkbenchPermissions(
        authentication()
    ));

    Authentication unauthenticated = mock(Authentication.class);
    when(unauthenticated.isAuthenticated()).thenReturn(false);
    assertAllDenied(AiAgentController.resolveWorkbenchPermissions(
        unauthenticated
    ));
    verify(unauthenticated, never()).getAuthorities();

    Authentication nullAuthorities = mock(Authentication.class);
    when(nullAuthorities.isAuthenticated()).thenReturn(true);
    when(nullAuthorities.getAuthorities()).thenReturn(null);
    assertAllDenied(AiAgentController.resolveWorkbenchPermissions(
        nullAuthorities
    ));
  }

  @Test
  void readsOneAuthenticationAuthoritySnapshot() {
    Authentication authentication = mock(Authentication.class);
    when(authentication.isAuthenticated()).thenReturn(true);
    doReturn(List.of(
        new SimpleGrantedAuthority("ai.workbench.agents.execute")
    )).when(authentication).getAuthorities();

    Map<String, Boolean> snapshot =
        AiAgentController.resolveWorkbenchPermissions(authentication);

    assertThat(snapshot)
        .containsEntry("execute", true)
        .containsEntry("view", false);
    verify(authentication, times(1)).getAuthorities();
  }

  @Test
  void everyHandlerUsesItsLeastPrivilegeAgentAuthority() {
    List<Method> handlers = Arrays.stream(
            AiAgentController.class.getDeclaredMethods()
        )
        .filter(AiAgentPermissionMatrixTest::isHandler)
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
        assertAgentAuthorities(methodName, Set.of(authority)));
    assertAgentAuthorities(
        "workbenchPermissions",
        Set.copyOf(EXPECTED_PERMISSIONS.values())
    );
  }

  @Test
  void seededAgentAuthoritiesMatchTheControllerSnapshot() throws IOException {
    JsonNode root = new ObjectMapper().readTree(agentResourceSeed().toFile());
    JsonNode agentResource = findResource(root, VIEW_AUTHORITY);

    assertThat(agentResource).isNotNull();
    Set<String> seededAuthorities = StreamSupport.stream(
            agentResource.path("children").spliterator(),
            false
        )
        .map(child -> child.path("code").asText())
        .collect(Collectors.toSet());
    seededAuthorities.add(agentResource.path("code").asText());

    assertThat(seededAuthorities)
        .containsExactlyInAnyOrderElementsOf(EXPECTED_PERMISSIONS.values());
  }

  private static void assertAgentAuthorities(
      final String methodName,
      final Set<String> expected
  ) {
    Method method = Arrays.stream(AiAgentController.class.getDeclaredMethods())
        .filter(candidate -> candidate.getName().equals(methodName))
        .findFirst()
        .orElseThrow();
    String expression = method.getAnnotation(PreAuthorize.class).value();
    Set<String> actual = AGENT_AUTHORITY_PATTERN.matcher(expression)
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

  private static Path agentResourceSeed() {
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
}
