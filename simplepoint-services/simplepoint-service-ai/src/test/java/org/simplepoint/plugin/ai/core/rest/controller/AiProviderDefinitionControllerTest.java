package org.simplepoint.plugin.ai.core.rest.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition;
import org.simplepoint.plugin.ai.core.api.model.AiProviderErrorCode;
import org.simplepoint.plugin.ai.core.api.model.AiProviderMessageCode;
import org.simplepoint.plugin.ai.core.api.model.AiProviderOperationException;
import org.simplepoint.plugin.ai.core.api.service.AiModelCatalogService;
import org.simplepoint.plugin.ai.core.api.service.AiProviderDefinitionService;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.ConnectionTestResult;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiProviderDefinitionControllerTest {

  private AiModelCatalogService modelCatalogService;

  private AiProviderDefinitionService providerService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    modelCatalogService = mock(AiModelCatalogService.class);
    providerService = mock(AiProviderDefinitionService.class);
    mockMvc = MockMvcBuilders.standaloneSetup(
        new AiProviderDefinitionController(
            providerService,
            modelCatalogService
        )
    ).build();
  }

  @Test
  void createAndModifyKeepTheirExistingCrudContract() throws Exception {
    AiProviderDefinition provider = new AiProviderDefinition();
    provider.setId("provider-1");
    provider.setCode("openai");
    when(providerService.create(any(AiProviderDefinition.class)))
        .thenReturn(provider);
    when(providerService.modifyById(any(AiProviderDefinition.class)))
        .thenReturn(provider);

    String request = """
        {"id":"provider-1","code":"openai"}
        """;
    mockMvc.perform(post("/workbench/providers")
            .contentType(MediaType.APPLICATION_JSON)
            .content(request))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("provider-1"));
    mockMvc.perform(put("/workbench/providers")
            .contentType(MediaType.APPLICATION_JSON)
            .content(request))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("provider-1"));

    verify(providerService).create(any(AiProviderDefinition.class));
    verify(providerService).modifyById(any(AiProviderDefinition.class));
  }

  @Test
  void createValidationFailureDoesNotExposeServerProse() throws Exception {
    String privateProse = "供应商编码已存在以及数据库租户细节";
    when(providerService.create(any(AiProviderDefinition.class)))
        .thenThrow(new IllegalArgumentException(privateProse));

    mockMvc.perform(post("/workbench/providers")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"duplicate\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.errorCode").value(
            AiProviderErrorCode.AI_PROVIDER_REQUEST_INVALID.name()
        ))
        .andExpect(content().string(not(containsString(privateProse))));
  }

  @Test
  void modifyStateFailureDoesNotExposeServerProse() throws Exception {
    String privateProse = "private provider state and network details";
    when(providerService.modifyById(any(AiProviderDefinition.class)))
        .thenThrow(new IllegalStateException(privateProse));

    mockMvc.perform(put("/workbench/providers")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"id\":\"provider-1\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.errorCode").value(
            AiProviderErrorCode.AI_PROVIDER_OPERATION_UNAVAILABLE.name()
        ))
        .andExpect(content().string(not(containsString(privateProse))));
  }

  @Test
  void removeUnexpectedFailureDoesNotExposeServerProse() throws Exception {
    String privateProse = "database topology and credential details";
    doThrow(new RuntimeException(privateProse))
        .when(providerService).removeByIds(any());

    mockMvc.perform(delete("/workbench/providers").param("ids", "provider-1"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.errorCode").value(
            AiProviderErrorCode.AI_PROVIDER_OPERATION_FAILED.name()
        ))
        .andExpect(content().string(not(containsString(privateProse))));
  }

  @Test
  void connectionSuccessReturnsMessageCodeInsteadOfMessage() throws Exception {
    when(modelCatalogService.testConnection("provider-1"))
        .thenReturn(new ConnectionTestResult(
            "provider-1",
            true,
            2,
            null,
            AiProviderMessageCode.CONNECTION_TEST_SUCCEEDED
        ));

    mockMvc.perform(post("/workbench/providers/provider-1/test"))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.messageCode").value(
            AiProviderMessageCode.CONNECTION_TEST_SUCCEEDED.name()
        ))
        .andExpect(jsonPath("$.message").doesNotExist());
  }

  @Test
  void testFailureReturnsOnlyStructuredStableCode() throws Exception {
    String privateProse = "upstream provider secret prose";
    when(modelCatalogService.testConnection("missing"))
        .thenThrow(new AiProviderOperationException(
            AiProviderErrorCode.AI_PROVIDER_NOT_FOUND,
            new IllegalStateException(privateProse)
        ));

    mockMvc.perform(post("/workbench/providers/missing/test"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.errorCode").value(
            AiProviderErrorCode.AI_PROVIDER_NOT_FOUND.name()
        ))
        .andExpect(content().string(not(containsString(privateProse))));
  }

  @Test
  void discoverFailureReturnsOnlyStructuredStableCode() throws Exception {
    when(modelCatalogService.discoverModels("disabled"))
        .thenThrow(new AiProviderOperationException(
            AiProviderErrorCode.AI_PROVIDER_DISABLED
        ));

    mockMvc.perform(get(
            "/workbench/providers/disabled/models/discover"
        ))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.errorCode").value(
            AiProviderErrorCode.AI_PROVIDER_DISABLED.name()
        ));
  }

  @Test
  void syncUnexpectedFailureDoesNotExposeExceptionText() throws Exception {
    String privateProse = "database topology and credential details";
    when(modelCatalogService.syncModels("provider-1"))
        .thenThrow(new IllegalStateException(privateProse));

    mockMvc.perform(post(
            "/workbench/providers/provider-1/models/sync"
        ))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.errorCode").value(
            AiProviderErrorCode.AI_PROVIDER_OPERATION_FAILED.name()
        ))
        .andExpect(content().string(not(containsString(privateProse))));
  }
}
