package org.simplepoint.plugin.ai.core.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.core.api.model.AiProviderVendor;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.DiscoveredPricing;

class AiDiscoveredPricingParserTest {

  private final AiDiscoveredPricingParser parser = new AiDiscoveredPricingParser();

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void parsesOpenRouterPerTokenPricing() throws Exception {
    DiscoveredPricing pricing = parser.parse(objectMapper.readTree("""
        {"pricing":{"prompt":"0.0000025","completion":"0.00001","request":"0.01"}}
        """));

    assertEquals(new BigDecimal("2.50000000"), pricing.inputTokenPrice());
    assertEquals(new BigDecimal("10.00000000"), pricing.outputTokenPrice());
    assertEquals(new BigDecimal("0.01000000"), pricing.requestPrice());
    assertEquals("USD", pricing.currency());
  }

  @Test
  void parsesPerMillionCostMetadataWithoutRescaling() throws Exception {
    DiscoveredPricing pricing = parser.parse(objectMapper.readTree("""
        {"cost":{"input":0.15,"output":0.6,"cache_read":0.02,"currency":"CNY"}}
        """));

    assertEquals(new BigDecimal("0.15000000"), pricing.inputTokenPrice());
    assertEquals(new BigDecimal("0.60000000"), pricing.outputTokenPrice());
    assertEquals(new BigDecimal("0.02000000"), pricing.cachedInputTokenPrice());
    assertEquals("CNY", pricing.currency());
  }

  @Test
  void parsesDashScopeNestedTokenAndRequestPrices() throws Exception {
    DiscoveredPricing pricing = parser.parse(objectMapper.readTree("""
        {"prices":[{"range_name":"Default","prices":[
          {"type":"input_token","price":"2","price_unit":"每百万tokens"},
          {"type":"output_token","price":"8","price_unit":"每百万tokens"},
          {"type":"image_number","price":"0.075","price_unit":"每张"}
        ]}]}
        """), AiProviderVendor.ALIBABA_QWEN);

    assertEquals("CNY", pricing.currency());
    assertEquals(new BigDecimal("2.00000000"), pricing.inputTokenPrice());
    assertEquals(new BigDecimal("8.00000000"), pricing.outputTokenPrice());
    assertEquals(new BigDecimal("0.07500000"), pricing.requestPrice());
  }

  @Test
  void returnsNullWhenNoPricingMetadataExists() throws Exception {
    assertNull(parser.parse(objectMapper.readTree("{\"id\":\"opaque-model\"}")));
  }
}
