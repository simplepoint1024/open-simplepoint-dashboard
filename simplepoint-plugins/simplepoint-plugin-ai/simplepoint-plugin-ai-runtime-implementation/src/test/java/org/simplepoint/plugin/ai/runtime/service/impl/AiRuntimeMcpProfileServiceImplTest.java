package org.simplepoint.plugin.ai.runtime.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeMcpDescriptor;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeMcpProfile;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeMcpRevision;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeImageResolution;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpDescriptorImportRequest;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileImportRequest;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfilePublishRequest;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileSpec;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileStatus;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeMcpProfileUpdateRequest;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeMcpDescriptorRepository;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeMcpProfileRepository;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimeMcpRevisionRepository;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeImageResolver;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimePoolRevisionService;

class AiRuntimeMcpProfileServiceImplTest {

  @Test
  void publishingFreezesRevisionAndLaterDraftEditDoesNotChangeIt() {
    final AiRuntimeMcpDescriptorRepository descriptors =
        mock(AiRuntimeMcpDescriptorRepository.class);
    final AiRuntimeMcpProfileRepository profiles =
        mock(AiRuntimeMcpProfileRepository.class);
    final AiRuntimeMcpRevisionRepository revisions =
        mock(AiRuntimeMcpRevisionRepository.class);
    final AiScopeAccessPolicy scopePolicy = mock(AiScopeAccessPolicy.class);
    AiRuntimePoolRevisionService poolRevisions =
        mock(AiRuntimePoolRevisionService.class);
    AiRuntimeImageResolver imageResolver = mock(AiRuntimeImageResolver.class);
    AiRuntimeMcpAdmissionService admissionService =
        mock(AiRuntimeMcpAdmissionService.class);
    when(scopePolicy.currentManagementScope()).thenReturn(
        new ScopeAssignment(AiResourceScope.SYSTEM, null)
    );
    when(profiles.findActiveByCodeForUpdate(any(), any(), any()))
        .thenReturn(Optional.empty());
    when(descriptors.findActiveByIdentity(any(), any(), any(), any(), any()))
        .thenReturn(Optional.empty());
    when(descriptors.save(any())).thenAnswer(invocation -> {
      AiRuntimeMcpDescriptor value = invocation.getArgument(0);
      value.setId("descriptor-1");
      return value;
    });
    when(profiles.save(any())).thenAnswer(invocation -> {
      AiRuntimeMcpProfile value = invocation.getArgument(0);
      if (value.getId() == null) {
        value.setId("profile-1");
      }
      return value;
    });
    when(revisions.nextRevisionNumber("profile-1")).thenReturn(1L);
    when(imageResolver.resolve("ghcr.io/example/server:1")).thenReturn(
        new RuntimeImageResolution("sha256:" + "a".repeat(64), null)
    );
    when(admissionService.admit(any(), any(), any(), any(), any()))
        .thenReturn(new AiRuntimeMcpAdmissionService.AdmissionResult(
            "b".repeat(64),
            "{\"mcpProbe\":{}}"
        ));
    when(revisions.save(any())).thenAnswer(invocation -> {
      AiRuntimeMcpRevision value = invocation.getArgument(0);
      value.setId("revision-1");
      return value;
    });
    AiRuntimeMcpProfileServiceImpl service =
        new AiRuntimeMcpProfileServiceImpl(
            descriptors,
            profiles,
            revisions,
            scopePolicy,
            poolRevisions,
            imageResolver,
            admissionService,
            new ObjectMapper()
        );
    RuntimeMcpProfileSpec initial = spec("ghcr.io/example/server:1");
    var imported = service.importDraft(new RuntimeMcpProfileImportRequest(
        "example",
        "Example",
        new RuntimeMcpDescriptorImportRequest(
            "catalog-1",
            "io.example/server",
            "1.0.0",
            "Example",
            "https://example.com/repository",
            "{\"name\":\"io.example/server\",\"version\":\"1.0.0\"}"
        ),
        initial
    ));
    when(profiles.findActiveByIdForUpdate("profile-1"))
        .thenReturn(Optional.of(imported.profile()));
    when(descriptors.findById("descriptor-1"))
        .thenReturn(Optional.of(imported.descriptor()));

    AiRuntimeMcpRevision published = service.publish(
        "profile-1",
        new RuntimeMcpProfilePublishRequest(
            "sha256:" + "a".repeat(64),
            null
        )
    );
    RuntimeMcpProfileSpec edited = spec("ghcr.io/example/server:2");
    AiRuntimeMcpProfile draft = service.update(
        "profile-1",
        new RuntimeMcpProfileUpdateRequest("Example v2", edited)
    );

    assertThat(published.getRevisionNumber()).isEqualTo(1L);
    assertThat(published.getAdmissionReportHash()).isEqualTo("b".repeat(64));
    assertThat(published.getAdmissionReportJson())
        .isEqualTo("{\"mcpProbe\":{}}");
    assertThat(published.getProfileSpec().artifact().reference())
        .isEqualTo("ghcr.io/example/server:1");
    assertThat(draft.getSpec().artifact().reference())
        .isEqualTo("ghcr.io/example/server:2");
    assertThat(draft.getStatus()).isEqualTo(RuntimeMcpProfileStatus.DRAFT);
    assertThat(draft.getActiveRevisionId()).isEqualTo("revision-1");
    verify(poolRevisions).activateAttached("profile-1", "revision-1");
  }

  @Test
  void deletesOnlyNeverPublishedDrafts() {
    final AiRuntimeMcpDescriptorRepository descriptors =
        mock(AiRuntimeMcpDescriptorRepository.class);
    final AiRuntimeMcpProfileRepository profiles =
        mock(AiRuntimeMcpProfileRepository.class);
    final AiRuntimeMcpRevisionRepository revisions =
        mock(AiRuntimeMcpRevisionRepository.class);
    final AiScopeAccessPolicy scopePolicy = mock(AiScopeAccessPolicy.class);
    AiRuntimeMcpProfile draft = new AiRuntimeMcpProfile();
    draft.setId("draft-1");
    draft.setScopeType(AiResourceScope.SYSTEM);
    draft.setStatus(RuntimeMcpProfileStatus.DRAFT);
    when(profiles.findActiveByIdForUpdate("draft-1"))
        .thenReturn(Optional.of(draft));
    when(revisions.findActiveByProfile("draft-1")).thenReturn(List.of());
    when(profiles.save(draft)).thenReturn(draft);
    AiRuntimeMcpProfileServiceImpl service = new AiRuntimeMcpProfileServiceImpl(
        descriptors,
        profiles,
        revisions,
        scopePolicy,
        mock(AiRuntimePoolRevisionService.class),
        mock(AiRuntimeImageResolver.class),
        mock(AiRuntimeMcpAdmissionService.class),
        new ObjectMapper()
    );

    service.removeDraft("draft-1");

    assertThat(draft.getDeletedAt()).isNotNull();
    verify(profiles).save(draft);

    AiRuntimeMcpProfile published = new AiRuntimeMcpProfile();
    published.setId("published-1");
    published.setScopeType(AiResourceScope.SYSTEM);
    published.setStatus(RuntimeMcpProfileStatus.PUBLISHED);
    published.setActiveRevisionId("revision-1");
    when(profiles.findActiveByIdForUpdate("published-1"))
        .thenReturn(Optional.of(published));

    assertThatThrownBy(() -> service.removeDraft("published-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("never-published");
  }

  private static RuntimeMcpProfileSpec spec(final String image) {
    return new RuntimeMcpProfileSpec(
        "io.example/server",
        "1.0.0",
        new RuntimeMcpProfileSpec.Artifact(
            RuntimeMcpProfileSpec.ArtifactSource.UPSTREAM_ORIGINAL,
            RuntimeMcpProfileSpec.ArtifactType.OCI,
            image
        ),
        new RuntimeMcpProfileSpec.Transport(
            RuntimeMcpProfileSpec.TransportType.STDIO,
            null,
            null
        ),
        new RuntimeMcpProfileSpec.Process(
            List.of(),
            List.of(),
            List.of(),
            null
        ),
        List.of(),
        List.of(),
        List.of(),
        new RuntimeMcpProfileSpec.NetworkPolicy(
            RuntimeMcpProfileSpec.NetworkMode.NONE,
            List.of()
        ),
        new RuntimeMcpProfileSpec.SessionPolicy(
            RuntimeMcpProfileSpec.SessionMode.DEDICATED,
            1
        ),
        new RuntimeMcpProfileSpec.SandboxPolicy(
            RuntimeMcpProfileSpec.SandboxProfile.STRICT,
            Map.of()
        )
    );
  }
}
