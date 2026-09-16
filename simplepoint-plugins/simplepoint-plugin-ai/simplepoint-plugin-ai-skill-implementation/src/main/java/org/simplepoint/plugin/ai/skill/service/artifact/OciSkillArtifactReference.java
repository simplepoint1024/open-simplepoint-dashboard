package org.simplepoint.plugin.ai.skill.service.artifact;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Strict parser for explicit OCI Registry references.
 */
record OciSkillArtifactReference(
    String registry,
    String repository,
    String selector
) {

  private static final Pattern REGISTRY = Pattern.compile(
      "^(?:localhost|[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?)"
          + "(?::[1-9][0-9]{0,4})?$"
  );

  private static final Pattern REPOSITORY = Pattern.compile(
      "^[a-z0-9]+(?:[._-][a-z0-9]+)*"
          + "(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*$"
  );

  private static final Pattern TAG = Pattern.compile(
      "^[A-Za-z0-9_][A-Za-z0-9._-]{0,127}$"
  );

  private static final Pattern DIGEST = Pattern.compile(
      "^sha256:[a-f0-9]{64}$"
  );

  static OciSkillArtifactReference parse(final String value) {
    String reference = value == null ? "" : value.trim();
    if (reference.isEmpty()
        || reference.length() > 512
        || reference.contains("://")
        || reference.contains("..")
        || reference.contains("?")
        || reference.contains("#")
        || reference.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException("OCI artifact reference is invalid");
    }
    int firstSlash = reference.indexOf('/');
    if (firstSlash <= 0 || firstSlash == reference.length() - 1) {
      throw new IllegalArgumentException(
          "OCI artifact reference must include an explicit Registry"
      );
    }
    String registry = reference.substring(0, firstSlash)
        .toLowerCase(Locale.ROOT);
    if (!REGISTRY.matcher(registry).matches()) {
      throw new IllegalArgumentException("OCI Registry host is invalid");
    }
    String remainder = reference.substring(firstSlash + 1);
    int digestMarker = remainder.lastIndexOf('@');
    String repository;
    String selector;
    if (digestMarker >= 0) {
      repository = remainder.substring(0, digestMarker);
      selector = remainder.substring(digestMarker + 1);
      if (!DIGEST.matcher(selector).matches()) {
        throw new IllegalArgumentException("OCI artifact digest selector is invalid");
      }
    } else {
      int lastSlash = remainder.lastIndexOf('/');
      int tagMarker = remainder.lastIndexOf(':');
      if (tagMarker <= lastSlash || tagMarker == remainder.length() - 1) {
        throw new IllegalArgumentException(
            "OCI artifact reference must include a tag or digest"
        );
      }
      repository = remainder.substring(0, tagMarker);
      selector = remainder.substring(tagMarker + 1);
      if (!TAG.matcher(selector).matches()) {
        throw new IllegalArgumentException("OCI artifact tag is invalid");
      }
    }
    if (!REPOSITORY.matcher(repository).matches()) {
      throw new IllegalArgumentException("OCI artifact repository is invalid");
    }
    return new OciSkillArtifactReference(registry, repository, selector);
  }

  String digestReference(final String digest) {
    return registry + "/" + repository + "@" + digest;
  }

  boolean digestSelected() {
    return selector.startsWith("sha256:");
  }
}
