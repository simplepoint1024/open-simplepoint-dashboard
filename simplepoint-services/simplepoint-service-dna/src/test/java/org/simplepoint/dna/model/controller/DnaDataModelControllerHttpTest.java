package org.simplepoint.dna.model.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.dna.model.service.DnaDataModelCatalog;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Exercises HTTP argument binding; production builds do not retain Java parameter names. */
class DnaDataModelControllerHttpTest {
  private DnaDataModelCatalog catalog;
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    catalog = mock(DnaDataModelCatalog.class);
    mvc = MockMvcBuilders.standaloneSetup(new DnaDataModelController(catalog))
        .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
  }

  @Test
  void listAndSourcePickerBindExplicitRequestParameters() throws Exception {
    when(catalog.list("orders", "DNA_SQL", 1, 5))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 5), 0));
    when(catalog.dataSources(any(), any(), eq(0), eq(20)))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

    mvc.perform(get("/platform/dna/models/data-models")
        .param("keyword", "orders").param("modelType", "DNA_SQL")
        .param("page", "1").param("size", "5")).andExpect(status().isOk());
    mvc.perform(get("/platform/dna/models/data-models/data-sources")
        .param("name", "source-filter")).andExpect(status().isOk());

    verify(catalog).list("orders", "DNA_SQL", 1, 5);
    verify(catalog).dataSources("source-filter", null, 0, 20);
  }

  @Test
  void standardTableLoadsSchemaAndPassesTypedFiltersToCatalog() throws Exception {
    when(catalog.schema()).thenReturn(new DnaDataModelCatalog.TableSchema(Map.of("type", "object"), List.of()));
    when(catalog.query(any(), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

    mvc.perform(get("/platform/dna/models/data-models/schema"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.schema.type").value("object"));
    mvc.perform(get("/platform/dna/models/data-models")
            .param("name", "{\"version\":1,\"where\":{\"field\":\"name\",\"op\":\"like\",\"value\":\"orders\"}}")
            .param("page", "0").param("size", "20").param("sort", "updatedAt,desc"))
        .andExpect(status().isOk());
    org.mockito.ArgumentCaptor<Map<String, String>> filters = org.mockito.ArgumentCaptor.forClass(Map.class);
    org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> page =
        org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
    verify(catalog).query(filters.capture(), page.capture());
    org.assertj.core.api.Assertions.assertThat(filters.getValue()).containsKey("name");
    org.assertj.core.api.Assertions.assertThat(page.getValue().getSort().getOrderFor("updatedAt"))
        .isNotNull();
  }

  @Test
  void mutationRoutesBindIdAndVersion() throws Exception {
    mvc.perform(put("/platform/dna/models/data-models/model-1")
        .contentType("application/json")
        .content("{\"name\":\"Orders\",\"modelType\":\"DNA_SQL\",\"version\":0}"))
        .andExpect(status().isOk());
    mvc.perform(delete("/platform/dna/models/data-models/model-1")
        .param("version", "3")).andExpect(status().isOk());

    verify(catalog).update(eq("model-1"), any());
    verify(catalog).delete("model-1", 3);
  }
}
