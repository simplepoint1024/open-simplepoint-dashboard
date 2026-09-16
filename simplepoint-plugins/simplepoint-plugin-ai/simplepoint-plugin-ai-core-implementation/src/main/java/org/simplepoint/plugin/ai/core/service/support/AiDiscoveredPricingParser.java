package org.simplepoint.plugin.ai.core.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import org.simplepoint.plugin.ai.core.api.model.AiProviderVendor;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderModels.DiscoveredPricing;
import org.springframework.stereotype.Component;

/** Normalizes common discovery pricing fields into the platform billing units. */
@Component
public class AiDiscoveredPricingParser {

  private static final BigDecimal MILLION = new BigDecimal("1000000");

  /** Parses pricing metadata, returning {@code null} when the endpoint exposes no prices. */
  public DiscoveredPricing parse(final JsonNode model) {
    return parse(model, null);
  }

  /** Parses pricing metadata with optional vendor-specific catalog normalization. */
  public DiscoveredPricing parse(final JsonNode model, final AiProviderVendor vendor) {
    if (model == null || !model.isObject()) {
      return null;
    }
    DiscoveredPricing vendorPricing = dashScopePricing(model, vendor);
    if (vendorPricing != null) {
      return vendorPricing;
    }
    BigDecimal input = first(model,
        "pricing.input_per_million", "pricing.inputPerMillion",
        "input_token_price", "inputTokenPrice", "cost.input");
    BigDecimal cachedInput = first(model,
        "pricing.cached_input_per_million", "pricing.cachedInputPerMillion",
        "cached_input_token_price", "cachedInputTokenPrice", "cost.cache_read");
    BigDecimal output = first(model,
        "pricing.output_per_million", "pricing.outputPerMillion",
        "output_token_price", "outputTokenPrice", "cost.output");
    if (input == null) {
      input = perToken(model, "pricing.prompt", "pricing.input",
          "input_cost_per_token", "inputCostPerToken");
    }
    if (cachedInput == null) {
      cachedInput = perToken(model, "pricing.input_cache_read", "pricing.cache_read",
          "cache_read_input_token_cost", "cachedInputCostPerToken");
    }
    if (output == null) {
      output = perToken(model, "pricing.completion", "pricing.output",
          "output_cost_per_token", "outputCostPerToken");
    }
    BigDecimal request = first(model, "pricing.request", "pricing.request_price",
        "request_price", "requestPrice", "cost.request");
    DiscoveredPricing pricing = new DiscoveredPricing(
        currency(model), input, cachedInput, output, request
    );
    return pricing.hasAnyPrice() ? pricing : null;
  }

  private static DiscoveredPricing dashScopePricing(
      final JsonNode model,
      final AiProviderVendor vendor
  ) {
    if (vendor != AiProviderVendor.ALIBABA_QWEN || !model.path("prices").isArray()) {
      return null;
    }
    JsonNode selectedTier = null;
    for (JsonNode tier : model.path("prices")) {
      if (selectedTier == null) {
        selectedTier = tier;
      }
      if ("default".equalsIgnoreCase(tier.path("range_name").asText())) {
        selectedTier = tier;
        break;
      }
    }
    if (selectedTier == null || !selectedTier.path("prices").isArray()) {
      return null;
    }
    BigDecimal input = null;
    BigDecimal cachedInput = null;
    BigDecimal output = null;
    BigDecimal request = null;
    for (JsonNode price : selectedTier.path("prices")) {
      BigDecimal value = decimal(price.path("price"));
      if (value == null) {
        continue;
      }
      String type = price.path("type").asText("").toLowerCase(Locale.ROOT);
      String unit = price.path("price_unit").asText("").toLowerCase(Locale.ROOT);
      if (type.contains("cached") || type.contains("cache")) {
        cachedInput = tokenPrice(value, unit);
      } else if (type.contains("input") && type.contains("token")) {
        input = tokenPrice(value, unit);
      } else if (type.contains("output") && type.contains("token")) {
        output = tokenPrice(value, unit);
      } else if (type.contains("request") || type.contains("number")
          || type.contains("image")) {
        request = normalize(value);
      }
    }
    DiscoveredPricing pricing = new DiscoveredPricing(
        currency(model, "CNY"), input, cachedInput, output, request
    );
    return pricing.hasAnyPrice() ? pricing : null;
  }

  private static BigDecimal decimal(final JsonNode node) {
    if (node == null || node.isNull() || node.isContainerNode()) {
      return null;
    }
    try {
      BigDecimal value = new BigDecimal(node.asText().trim());
      return value.signum() < 0 ? null : value;
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static BigDecimal tokenPrice(final BigDecimal value, final String unit) {
    if (unit.contains("百万") || unit.contains("million")) {
      return normalize(value);
    }
    if (unit.contains("千") || unit.contains("thousand")) {
      return normalize(value.multiply(new BigDecimal("1000")));
    }
    return normalize(value.multiply(MILLION));
  }

  private static BigDecimal perToken(final JsonNode node, final String... paths) {
    BigDecimal value = first(node, paths);
    return value == null ? null : normalize(value.multiply(MILLION));
  }

  private static BigDecimal first(final JsonNode root, final String... paths) {
    for (String path : paths) {
      JsonNode node = at(root, path);
      if (node == null || node.isNull() || node.isContainerNode()) {
        continue;
      }
      try {
        BigDecimal value = new BigDecimal(node.asText().trim());
        if (value.signum() >= 0) {
          return normalize(value);
        }
      } catch (NumberFormatException ignored) {
        // A malformed optional price must not make the whole catalog unavailable.
      }
    }
    return null;
  }

  private static JsonNode at(final JsonNode root, final String path) {
    JsonNode current = root;
    for (String part : path.split("\\.")) {
      current = current == null ? null : current.get(part);
    }
    return current;
  }

  private static String currency(final JsonNode model) {
    return currency(model, "USD");
  }

  private static String currency(final JsonNode model, final String fallback) {
    for (String path : new String[] {"pricing.currency", "cost.currency", "currency"}) {
      JsonNode node = at(model, path);
      if (node != null && node.isTextual() && node.asText().trim().matches("[A-Za-z]{3}")) {
        return node.asText().trim().toUpperCase(Locale.ROOT);
      }
    }
    return fallback;
  }

  private static BigDecimal normalize(final BigDecimal value) {
    return value.setScale(8, RoundingMode.HALF_UP);
  }
}
