package org.simplepoint.plugin.ai.catalog.api.model;

import java.util.List;
import java.util.Map;

/**
 * Normalized, immutable view of an upstream MCP {@code server.json} document.
 *
 * <p>The original JSON remains authoritative. This model deliberately keeps
 * registry and transport identifiers as strings so a newer Registry value is
 * not discarded by an older SimplePoint release.</p>
 */
public record McpServerDescriptor(
    String schemaUrl,
    String name,
    String title,
    String description,
    String version,
    Repository repository,
    String websiteUrl,
    List<Package> packages,
    List<Transport> remotes,
    String rawJson
) {

  /** Normalizes descriptor collections and required identity fields. */
  public McpServerDescriptor {
    name = required(name, "MCP descriptor name");
    description = required(description, "MCP descriptor description");
    version = required(version, "MCP descriptor version");
    packages = immutable(packages);
    remotes = immutable(remotes);
    rawJson = required(rawJson, "MCP descriptor raw JSON");
  }

  /** Upstream source repository. */
  public record Repository(
      String url,
      String source,
      String id,
      String subfolder
  ) {

    /** Validates the upstream repository identity. */
    public Repository {
      url = required(url, "MCP repository URL");
      source = required(source, "MCP repository source");
    }
  }

  /** One package distribution declared by the upstream publisher. */
  public record Package(
      String registryType,
      String identifier,
      String version,
      String registryBaseUrl,
      String fileSha256,
      String runtimeHint,
      Transport transport,
      List<Argument> runtimeArguments,
      List<Argument> packageArguments,
      List<Input> environmentVariables
  ) {

    /** Normalizes package inputs and validates the package identity. */
    public Package {
      registryType = required(registryType, "MCP package registry type");
      identifier = required(identifier, "MCP package identifier");
      if (transport == null) {
        throw new IllegalArgumentException("MCP package transport is required");
      }
      runtimeArguments = immutable(runtimeArguments);
      packageArguments = immutable(packageArguments);
      environmentVariables = immutable(environmentVariables);
    }
  }

  /** Local or remote transport declared by the Registry schema. */
  public record Transport(
      String type,
      String url,
      List<Input> headers,
      Map<String, Input> variables
  ) {

    /** Normalizes transport headers and template variables. */
    public Transport {
      type = required(type, "MCP transport type");
      headers = immutable(headers);
      variables = variables == null ? Map.of() : Map.copyOf(variables);
    }
  }

  /** Named or positional command argument from the Registry schema. */
  public record Argument(
      String type,
      String name,
      String valueHint,
      boolean repeated,
      Input input,
      Map<String, Input> variables
  ) {

    /** Validates named and positional Registry argument invariants. */
    public Argument {
      type = required(type, "MCP argument type");
      if (!"named".equals(type) && !"positional".equals(type)) {
        throw new IllegalArgumentException("Unsupported MCP argument type: " + type);
      }
      if ("named".equals(type)) {
        name = required(name, "Named MCP argument name");
      } else if ((valueHint == null || valueHint.isBlank())
          && (input == null || input.value() == null || input.value().isBlank())) {
        throw new IllegalArgumentException(
            "Positional MCP argument requires valueHint or value"
        );
      }
      input = input == null ? Input.empty() : input;
      variables = variables == null ? Map.of() : Map.copyOf(variables);
    }
  }

  /** Configurable input used by arguments, environment, headers and URLs. */
  public record Input(
      String name,
      String description,
      String format,
      boolean required,
      boolean secret,
      String defaultValue,
      String placeholder,
      String value,
      List<String> choices
  ) {

    /** Applies Registry defaults and freezes selectable choices. */
    public Input {
      format = format == null || format.isBlank() ? "string" : format;
      choices = immutable(choices);
    }

    private static Input empty() {
      return new Input(null, null, "string", false, false, null, null, null, List.of());
    }
  }

  private static String required(final String value, final String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value.trim();
  }

  private static <T> List<T> immutable(final List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }
}
