package org.simplepoint.plugin.ai.mcp.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpProviderConnectionRepository;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpServerDefinitionRepository;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class McpCatalogServiceTest {
  final AiMcpServerDefinitionRepository repository = mock(AiMcpServerDefinitionRepository.class);
  final AiScopeAccessPolicy policy = mock(AiScopeAccessPolicy.class);
  final AiMcpProviderConnectionRepository connections = mock(AiMcpProviderConnectionRepository.class);
  final AiMcpServerDefinitionServiceImpl service = new AiMcpServerDefinitionServiceImpl(repository, null, null, null,
      connections, null, policy, null, null, null, null, new ObjectMapper(), null, null);

  @Test
  void catalogPagingUsesTheExactManagementScopeAndEscapesLiteralSearchWithoutDecoration() {
    when(policy.currentManagementScope()).thenReturn(new AiScopeAccessPolicy.ScopeAssignment(AiResourceScope.TENANT, "actual-tenant"));
    var request = PageRequest.of(26, 20);
    when(repository.findCatalogInScope(AiResourceScope.TENANT, "actual-tenant", "%!%!_!!%", request))
        .thenReturn(new PageImpl<>(List.of(), request, 521));
    assertThat(service.findCatalog(" %_! ", request).getTotalElements()).isEqualTo(521);
    verify(repository).findCatalogInScope(AiResourceScope.TENANT, "actual-tenant", "%!%!_!!%", request);
    verifyNoInteractions(connections);
  }

  @Test
  void catalogCannotRequestUnboundedOrArbitrarilySortedData() {
    for (var request : List.of(org.springframework.data.domain.Pageable.unpaged(), PageRequest.of(0, 101),
        PageRequest.of(0, 20, org.springframework.data.domain.Sort.by("credentialCiphertext")), PageRequest.of(Integer.MAX_VALUE, 20))) {
      assertThatThrownBy(() -> service.findCatalog(null, request)).isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> service.findCatalog("a".repeat(201), PageRequest.of(0, 20))).isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(repository, policy);
  }
}
