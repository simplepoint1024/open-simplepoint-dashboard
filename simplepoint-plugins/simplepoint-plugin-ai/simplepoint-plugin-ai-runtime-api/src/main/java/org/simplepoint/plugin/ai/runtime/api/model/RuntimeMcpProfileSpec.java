package org.simplepoint.plugin.ai.runtime.api.model;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Platform-owned runtime overlay for an immutable upstream MCP descriptor.
 *
 * <p>This contract contains configuration and secret references, never secret
 * values. It is independent from OCI image labels and can therefore describe
 * unmodified upstream images.</p>
 */
public record RuntimeMcpProfileSpec(
    String descriptorName,
    String descriptorVersion,
    Artifact artifact,
    Transport transport,
    Process process,
    List<ConfigurationBinding> configuration,
    List<SecretBinding> secrets,
    List<StorageBinding> storage,
    NetworkPolicy network,
    SessionPolicy session,
    SandboxPolicy sandbox
) {

  private static final Pattern ENVIRONMENT_NAME =
      Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  private static final Pattern NETWORK_HOST = Pattern.compile(
      "^(?:\\*\\.)?[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$"
  );

  private static final Set<String> SHELL_EXECUTABLES = Set.of(
      "sh", "bash", "dash", "ash", "zsh", "ksh"
  );

  private static final Pattern PROVIDER_SECRET_REFERENCE = Pattern.compile(
      "^provider://[a-z0-9][a-z0-9._-]{0,63}/access-token$"
  );

  /** Validates and normalizes the complete runtime profile. */
  public RuntimeMcpProfileSpec {
    descriptorName = required(descriptorName, "MCP descriptor name");
    descriptorVersion = required(descriptorVersion, "MCP descriptor version");
    if (artifact == null || transport == null) {
      throw new IllegalArgumentException("MCP artifact and transport are required");
    }
    process = process == null ? Process.empty() : process;
    configuration = immutable(configuration);
    secrets = immutable(secrets);
    storage = immutable(storage);
    if (storage.size() > 16
        || storage.stream().map(StorageBinding::targetPath).distinct().count()
            != storage.size()) {
      throw new IllegalArgumentException(
          "Storage targets must be unique and bounded"
      );
    }
    if (!process.initializationCommand().isEmpty()
        && (artifact.type() != ArtifactType.OCI
            || storage.stream().noneMatch(binding ->
                !binding.readOnly()
                    && binding.type() != StorageType.TMPFS
                    && binding.type() != StorageType.EPHEMERAL))) {
      throw new IllegalArgumentException(
          "Storage initialization requires writable managed OCI storage"
      );
    }
    network = network == null ? NetworkPolicy.none() : network;
    session = session == null ? SessionPolicy.dedicated() : session;
    sandbox = sandbox == null ? SandboxPolicy.strict() : sandbox;
    if (sandbox.profile() == SandboxProfile.BROWSER
        && storage.stream().noneMatch(binding ->
            binding.type() == StorageType.TMPFS
                && "/dev/shm".equals(binding.targetPath()))) {
      throw new IllegalArgumentException(
          "Browser sandbox requires a bounded /dev/shm tmpfs"
      );
    }
    if (sandbox.profile() == SandboxProfile.WORKSPACE
        && storage.stream().noneMatch(binding ->
            binding.type() == StorageType.WORKSPACE_RO
                || binding.type() == StorageType.WORKSPACE_RW)) {
      throw new IllegalArgumentException(
          "Workspace sandbox requires a managed workspace mount"
      );
    }
    if (sandbox.profile() == SandboxProfile.DATA
        && network.mode() != NetworkMode.TCP_EGRESS
        && network.mode() != NetworkMode.INTERNAL_SERVICE) {
      throw new IllegalArgumentException(
          "Data sandbox requires an exact TCP or internal-service policy"
      );
    }
    if (artifact.type() == ArtifactType.REMOTE
        && transport.type() == TransportType.STDIO) {
      throw new IllegalArgumentException("Remote MCP artifacts cannot use stdio");
    }
    if (artifact.type() != ArtifactType.REMOTE
        && transport.type() != TransportType.STDIO
        && (transport.containerPort() == null
            || transport.path() == null)) {
      throw new IllegalArgumentException(
          "Container HTTP transports require a container port and path"
      );
    }
  }

  /** MCP artifact identity and provenance. */
  public record Artifact(
      ArtifactSource source,
      ArtifactType type,
      String reference
  ) {

    /** Validates artifact identity and provenance. */
    public Artifact {
      if (source == null || type == null) {
        throw new IllegalArgumentException("Artifact source and type are required");
      }
      reference = required(reference, "Artifact reference");
    }
  }

  /** Transport selected by the platform rather than by private OCI labels. */
  public record Transport(
      TransportType type,
      Integer containerPort,
      String path
  ) {

    /** Validates transport-specific container addressing. */
    public Transport {
      if (type == null) {
        throw new IllegalArgumentException("MCP transport type is required");
      }
      if (containerPort != null
          && (containerPort < 1 || containerPort > 65535)) {
        throw new IllegalArgumentException("Container port is invalid");
      }
      if (path != null && (!path.startsWith("/")
          || path.startsWith("//")
          || path.contains("..")
          || path.indexOf('?') >= 0
          || path.indexOf('#') >= 0
          || path.indexOf('%') >= 0
          || path.indexOf('\\') >= 0
          || path.chars().anyMatch(character ->
              character <= 0x20 || character == 0x7f))) {
        throw new IllegalArgumentException("MCP transport path is invalid");
      }
    }
  }

  /** Array-only process definition. Shell command strings are not accepted. */
  public record Process(
      List<String> entrypoint,
      List<String> command,
      List<String> arguments,
      String workingDirectory,
      ProcessUserMode userMode,
      List<String> initializationCommand
  ) {

    /** Freezes process arrays and rejects implicit shell execution. */
    public Process {
      entrypoint = tokens(entrypoint, "entrypoint");
      command = tokens(command, "command");
      arguments = tokens(arguments, "argument");
      if (workingDirectory != null) {
        validateContainerPath(workingDirectory, "Working directory", false);
      }
      userMode = userMode == null
          ? ProcessUserMode.RUNTIME_DEFAULT : userMode;
      initializationCommand = tokens(
          initializationCommand,
          "initialization command"
      );
      rejectShellCommand(entrypoint, command, arguments);
      rejectShellCommand(
          initializationCommand,
          List.of(),
          List.of()
      );
    }

    /** Preserves fixed non-root execution for existing Profile callers. */
    public Process(
        final List<String> entrypoint,
        final List<String> command,
        final List<String> arguments,
        final String workingDirectory
    ) {
      this(
          entrypoint,
          command,
          arguments,
          workingDirectory,
          ProcessUserMode.RUNTIME_DEFAULT,
          List.of()
      );
    }

    /** Preserves construction when no storage initializer is required. */
    public Process(
        final List<String> entrypoint,
        final List<String> command,
        final List<String> arguments,
        final String workingDirectory,
        final ProcessUserMode userMode
    ) {
      this(
          entrypoint,
          command,
          arguments,
          workingDirectory,
          userMode,
          List.of()
      );
    }

    private static Process empty() {
      return new Process(
          List.of(),
          List.of(),
          List.of(),
          null,
          ProcessUserMode.RUNTIME_DEFAULT,
          List.of()
      );
    }
  }

  /** Non-sensitive runtime configuration. */
  public record ConfigurationBinding(
      String name,
      String targetEnvironment,
      String value
  ) {

    /** Validates a non-sensitive environment binding. */
    public ConfigurationBinding {
      name = required(name, "Configuration binding name");
      validateEnvironment(targetEnvironment);
      if (value == null) {
        throw new IllegalArgumentException("Configuration value must not be null");
      }
    }
  }

  /** Reference-only secret binding. */
  public record SecretBinding(
      String name,
      String secretReference,
      SecretTarget target,
      String targetName
  ) {

    /** Validates a reference-only secret target. */
    public SecretBinding {
      name = required(name, "Secret binding name");
      secretReference = required(secretReference, "Secret reference");
      if (secretReference.startsWith("provider://")
          && !PROVIDER_SECRET_REFERENCE.matcher(secretReference).matches()) {
        throw new IllegalArgumentException(
            "Provider secret reference is invalid"
        );
      }
      if (target == null) {
        throw new IllegalArgumentException("Secret target is required");
      }
      targetName = required(targetName, "Secret target name");
      if (target == SecretTarget.ENV_AT_EXEC) {
        validateEnvironment(targetName);
      } else {
        validateContainerPath(targetName, "Secret file target", false);
        if (!targetName.startsWith("/run/secrets/simplepoint/")) {
          throw new IllegalArgumentException(
              "Secret file target must be below /run/secrets/simplepoint"
          );
        }
      }
    }
  }

  /** Platform-resolved storage reference and container target. */
  public record StorageBinding(
      String name,
      StorageType type,
      String storageReference,
      String targetPath,
      boolean readOnly,
      Long sizeBytes
  ) {

    /** Validates a platform-owned container storage target. */
    public StorageBinding {
      name = required(name, "Storage binding name");
      if (type == null) {
        throw new IllegalArgumentException("Storage type is required");
      }
      if (type != StorageType.TMPFS) {
        storageReference = required(storageReference, "Storage reference");
      }
      validateContainerPath(targetPath, "Storage target", false);
      if (sizeBytes != null && sizeBytes <= 0) {
        throw new IllegalArgumentException("Storage size must be positive");
      }
      if ((type == StorageType.WORKSPACE_RO
          || type == StorageType.OBJECT_SNAPSHOT) && !readOnly) {
        throw new IllegalArgumentException(
            "Read-only storage classes cannot be mounted writable"
        );
      }
      if (sizeBytes != null && type != StorageType.TMPFS
          && type != StorageType.EPHEMERAL) {
        throw new IllegalArgumentException(
            "Only tmpfs and ephemeral storage accept a runtime size limit"
        );
      }
      if (targetPath.startsWith("/run/secrets/simplepoint")
          || targetPath.startsWith("/run/simplepoint-runtime")) {
        throw new IllegalArgumentException(
            "Storage target overlaps a reserved Runtime path"
        );
      }
    }
  }

  /** Outbound policy resolved by the Runtime control plane. */
  public record NetworkPolicy(
      NetworkMode mode,
      List<String> allowlist
  ) {

    /** Validates network access and its required allowlist. */
    public NetworkPolicy {
      mode = mode == null ? NetworkMode.NONE : mode;
      allowlist = immutable(allowlist);
      if (allowlist.size() > 32
          || allowlist.stream().distinct().count() != allowlist.size()) {
        throw new IllegalArgumentException(
            "Network allowlist must be unique and bounded"
        );
      }
      if (mode == NetworkMode.NONE && !allowlist.isEmpty()) {
        throw new IllegalArgumentException(
            "Network allowlist must be empty when network mode is NONE"
        );
      }
      if (mode != NetworkMode.NONE && allowlist.isEmpty()) {
        throw new IllegalArgumentException(
            "Network allowlist is required when network access is enabled"
        );
      }
      for (String value : allowlist) {
        validateNetworkTarget(mode, value);
      }
    }

    private static NetworkPolicy none() {
      return new NetworkPolicy(NetworkMode.NONE, List.of());
    }
  }

  /** Workload sharing and affinity contract. */
  public record SessionPolicy(
      SessionMode mode,
      int maxSessions
  ) {

    /** Validates session sharing and concurrency. */
    public SessionPolicy {
      mode = mode == null ? SessionMode.DEDICATED : mode;
      if (maxSessions <= 0) {
        throw new IllegalArgumentException("Maximum sessions must be positive");
      }
      if (mode == SessionMode.DEDICATED && maxSessions != 1) {
        throw new IllegalArgumentException(
            "Dedicated MCP workloads must have exactly one session"
        );
      }
    }

    private static SessionPolicy dedicated() {
      return new SessionPolicy(SessionMode.DEDICATED, 1);
    }
  }

  /** Named sandbox class with bounded, policy-reviewed options. */
  public record SandboxPolicy(
      SandboxProfile profile,
      Map<String, Long> limits
  ) {

    /** Freezes the bounded sandbox limit overrides. */
    public SandboxPolicy {
      profile = profile == null ? SandboxProfile.STRICT : profile;
      limits = limits == null ? Map.of() : Map.copyOf(limits);
      if (limits.values().stream().anyMatch(value -> value == null || value <= 0)) {
        throw new IllegalArgumentException("Sandbox limits must be positive");
      }
    }

    private static SandboxPolicy strict() {
      return new SandboxPolicy(SandboxProfile.STRICT, Map.of());
    }
  }

  /** Selects the platform identity or the immutable image's declared user. */
  public enum ProcessUserMode {
    RUNTIME_DEFAULT,
    IMAGE_DEFAULT
  }

  /** Provenance class for a selected MCP artifact. */
  public enum ArtifactSource {
    UPSTREAM_ORIGINAL,
    CATALOG_ORIGINAL,
    PLATFORM_BUILT
  }

  /** Distribution format selected from an upstream descriptor. */
  public enum ArtifactType {
    OCI,
    NPM,
    PYPI,
    REMOTE
  }

  /** Supported MCP transport adapters. */
  public enum TransportType {
    STDIO,
    STREAMABLE_HTTP,
    SSE_LEGACY
  }

  /** Safe secret materialization target. */
  public enum SecretTarget {
    FILE,
    ENV_AT_EXEC
  }

  /** Platform-owned workload storage class. */
  public enum StorageType {
    TMPFS,
    EPHEMERAL,
    WORKSPACE_RO,
    WORKSPACE_RW,
    PERSISTENT_VOLUME,
    OBJECT_SNAPSHOT
  }

  /** Outbound workload network policy class. */
  public enum NetworkMode {
    NONE,
    HTTP_EGRESS,
    TCP_EGRESS,
    INTERNAL_SERVICE
  }

  /** MCP client-to-workload sharing policy. */
  public enum SessionMode {
    DEDICATED,
    POOL_AFFINE,
    SHARED_STATELESS
  }

  /** Named, administrator-reviewed workload sandbox. */
  public enum SandboxProfile {
    STRICT,
    DATA,
    BROWSER,
    WORKSPACE,
    LEGACY
  }

  @SafeVarargs
  private static void rejectShellCommand(final List<String>... tokenGroups) {
    List<String> tokens = new java.util.ArrayList<>();
    for (List<String> group : tokenGroups) {
      tokens.addAll(group);
    }
    for (int index = 0; index + 1 < tokens.size(); index++) {
      String executable = tokens.get(index);
      int separator = executable.lastIndexOf('/');
      String base = executable.substring(separator + 1)
          .toLowerCase(Locale.ROOT);
      if (SHELL_EXECUTABLES.contains(base) && "-c".equals(tokens.get(index + 1))) {
        throw new IllegalArgumentException("Shell -c execution is not allowed");
      }
    }
  }

  private static List<String> tokens(
      final List<String> values,
      final String label
  ) {
    List<String> result = immutable(values);
    if (result.size() > 128) {
      throw new IllegalArgumentException("Too many MCP process " + label + " tokens");
    }
    for (String value : result) {
      if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
        throw new IllegalArgumentException("Invalid MCP process " + label);
      }
    }
    return result;
  }

  private static void validateEnvironment(final String value) {
    if (value == null || !ENVIRONMENT_NAME.matcher(value).matches()) {
      throw new IllegalArgumentException("Environment variable name is invalid");
    }
  }

  private static void validateNetworkTarget(
      final NetworkMode mode,
      final String value
  ) {
    String target = value == null ? "" : value.trim();
    int separator = target.lastIndexOf(':');
    String host = separator < 0 ? target : target.substring(0, separator);
    String port = separator < 0 ? "" : target.substring(separator + 1);
    if (host.isEmpty() || host.length() > 253 || host.contains("..")
        || !NETWORK_HOST.matcher(host).matches()) {
      throw new IllegalArgumentException("Network allowlist target is invalid");
    }
    if (mode == NetworkMode.HTTP_EGRESS) {
      if (!port.isEmpty() && !"80".equals(port) && !"443".equals(port)) {
        throw new IllegalArgumentException(
            "HTTP egress supports only port 80 or 443"
        );
      }
      return;
    }
    if (host.startsWith("*.") || port.isEmpty()) {
      throw new IllegalArgumentException(
          "TCP and internal-service targets require an exact host:port"
      );
    }
    try {
      int parsed = Integer.parseInt(port);
      if (parsed < 1 || parsed > 65535) {
        throw new IllegalArgumentException(
            "Network allowlist port is invalid"
        );
      }
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException(
          "Network allowlist port is invalid", ex
      );
    }
  }

  private static void validateContainerPath(
      final String value,
      final String label,
      final boolean allowRoot
  ) {
    if (value == null || !value.startsWith("/") || value.contains("..")
        || value.indexOf('\0') >= 0 || (!allowRoot && "/".equals(value))) {
      throw new IllegalArgumentException(label + " is invalid");
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
