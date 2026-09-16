package org.simplepoint.plugin.ai.mcp.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpInvocation;
import org.simplepoint.plugin.ai.mcp.api.repository.AiMcpInvocationRepository;
import org.simplepoint.plugin.ai.runtime.api.repository.AiRuntimePoolRepository;

class AiMcpInvocationLedgerTest {

  @Test
  void failureLedgerNeverPersistsRemoteErrorOrCredentialMaterial() {
    AiMcpInvocationRepository repository = mock(
        AiMcpInvocationRepository.class
    );
    AiRuntimePoolRepository poolRepository = mock(
        AiRuntimePoolRepository.class
    );
    AiMcpInvocation invocation = new AiMcpInvocation();
    invocation.setStartedAt(Instant.now());
    when(repository.findById("invocation-1"))
        .thenReturn(Optional.of(invocation));

    AiMcpInvocationLedger ledger = new AiMcpInvocationLedger(
        repository, new ObjectMapper(), poolRepository
    );
    ledger.fail(
        "invocation-1",
        new IllegalStateException(
            "Bearer ghp_must_not_be_stored postgres://user:secret@db/test"
        )
    );

    ArgumentCaptor<AiMcpInvocation> captor = ArgumentCaptor.forClass(
        AiMcpInvocation.class
    );
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getErrorMessage())
        .isEqualTo("Remote MCP operation failed")
        .doesNotContain("ghp_", "postgres://", "secret");
  }
}
