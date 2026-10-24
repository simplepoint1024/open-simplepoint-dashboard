package org.simplepoint.installation.host;

import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.UUID;

/** Distinct durable stage operations derived from one immutable deployment operation. */
final class InstallationOperation {
  private InstallationOperation() {}

  static UUID stage(Properties properties, String stage) {
    UUID deployment = UUID.fromString(InstallationMain.required(properties, "installation.operation"));
    return UUID.nameUUIDFromBytes((deployment + ":" + stage).getBytes(StandardCharsets.UTF_8));
  }
}
