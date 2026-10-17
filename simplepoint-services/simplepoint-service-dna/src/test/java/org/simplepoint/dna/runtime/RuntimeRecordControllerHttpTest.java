package org.simplepoint.dna.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.service.RecordCommandService;
import org.simplepoint.plugin.dna.runtime.api.service.RecordQueryService;
import org.simplepoint.plugin.dna.runtime.api.service.RecordTransactionService;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RuntimeRecordControllerHttpTest {

  private RecordQueryService queryService;
  private RecordCommandService commandService;
  private RecordTransactionService transactionService;
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    queryService = mock(RecordQueryService.class);
    commandService = mock(RecordCommandService.class);
    transactionService = mock(RecordTransactionService.class);
    mvc = MockMvcBuilders.standaloneSetup(new RuntimeRecordController(
        queryService,
        commandService,
        transactionService
    )).setControllerAdvice(new RuntimeExceptionHandler()).build();
  }

  @Test
  void createBindsTheMandatoryIdempotencyKey() throws Exception {
    when(commandService.create(any())).thenReturn(new RecordCommandResult(
        RecordMutationType.CREATE,
        "record-1",
        0,
        1,
        Instant.parse("2026-10-13T01:02:03Z")
    ));

    mvc.perform(post("/internal/dna/runtime/records/create")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "physicalModelId":"model-1",
                  "schemaReference":{"version":1,"checksum":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                  "idempotencyKey":"form-submit-1",
                  "values":{"title":"Open"}
                }
                """))
        .andExpect(status().isOk());

    ArgumentCaptor<RecordCreateCommand> command =
        ArgumentCaptor.forClass(RecordCreateCommand.class);
    verify(commandService).create(command.capture());
    assertThat(command.getValue().idempotencyKey()).isEqualTo("form-submit-1");
    assertThat(command.getValue().writePolicyVersion()).isZero();
  }

  @Test
  void updateBindsTheExplicitAdmissionVersion() throws Exception {
    mvc.perform(post("/internal/dna/runtime/records/update").contentType(MediaType.APPLICATION_JSON).content("""
        {
          "physicalModelId":"model-1",
          "schemaReference":{"version":1,"checksum":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
          "recordId":"record-1","expectedVersion":0,"values":{"title":"Changed"},"writePolicyVersion":7
        }
        """)).andExpect(status().isOk());
    var command = ArgumentCaptor.forClass(RecordUpdateCommand.class);
    verify(commandService).update(command.capture());
    assertThat(command.getValue().writePolicyVersion()).isEqualTo(7);
  }

  @Test
  void deleteBindsLegacyRequestsToTheInitialAdmissionVersion() throws Exception {
    mvc.perform(post("/internal/dna/runtime/records/delete").contentType(MediaType.APPLICATION_JSON).content("""
        {
          "physicalModelId":"model-1",
          "schemaReference":{"version":1,"checksum":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
          "recordId":"record-1","expectedVersion":0
        }
        """)).andExpect(status().isOk());
    var command = ArgumentCaptor.forClass(RecordDeleteCommand.class);
    verify(commandService).delete(command.capture());
    assertThat(command.getValue().writePolicyVersion()).isZero();
  }

  @Test
  void pageBindsTheBoundedStructuredQueryContract() throws Exception {
    when(queryService.page(any())).thenReturn(new RecordPageView(0, 20, 0, List.of()));

    mvc.perform(post("/internal/dna/runtime/records/page")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "physicalModelId":"model-1",
                  "schemaReference":{"version":1,"checksum":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                  "page":0,
                  "size":20,
                  "fields":["title"],
                  "filter":{"operator":"EQUALS","field":"title","values":["Open"]},
                  "sorts":[{"field":"title","direction":"ASC"}]
                }
                """))
        .andExpect(status().isOk());

    ArgumentCaptor<RecordPageQuery> query = ArgumentCaptor.forClass(RecordPageQuery.class);
    verify(queryService).page(query.capture());
    assertThat(query.getValue().filter().field()).isEqualTo("title");
    assertThat(query.getValue().sorts()).hasSize(1);
  }

  @Test
  void transactionUsesAnExplicitMutationDiscriminator() throws Exception {
    when(transactionService.execute(any()))
        .thenReturn(new RecordTransactionResult(List.of(), 1));

    mvc.perform(post("/internal/dna/runtime/records/transaction")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "physicalModelId":"model-1",
                  "schemaReference":{"version":1,"checksum":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
                  "timeoutMs":10000,
                  "mutations":[
                    {"type":"CREATE","reference":"c","values":{"title":"Open"}},
                    {"type":"UPDATE","reference":"u","recordId":"record-1",
                     "expectedVersion":0,"values":{"title":"Closed"}},
                    {"type":"DELETE","reference":"d","recordId":"record-2",
                     "expectedVersion":1}
                  ]
                }
                """))
        .andExpect(status().isOk());

    ArgumentCaptor<RecordTransactionCommand> command =
        ArgumentCaptor.forClass(RecordTransactionCommand.class);
    verify(transactionService).execute(command.capture());
    assertThat(command.getValue().writePolicyVersion()).isZero();
    assertThat(command.getValue().mutations())
        .hasExactlyElementsOfTypes(
            RecordCreateMutation.class,
            RecordUpdateMutation.class,
            RecordDeleteMutation.class
        );
  }
}
