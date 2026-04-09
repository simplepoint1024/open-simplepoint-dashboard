package org.simplepoint.plugin.dna.core.api.spi;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;

/**
 * SPI for persisting downloaded JDBC driver artifacts.
 */
public interface JdbcDriverArtifactStorage {

  /**
   * Stores a downloaded driver artifact.
   *
   * @param driver      driver definition
   * @param downloadUri remote artifact URI
   * @param inputStream artifact stream
   * @return stored file path
   * @throws IOException on storage failure
   */
  Path store(JdbcDriverDefinition driver, URI downloadUri, InputStream inputStream) throws IOException;

  /**
   * Stores an uploaded driver artifact.
   *
   * @param driver      driver definition
   * @param sourceName  uploaded source name
   * @param inputStream artifact stream
   * @return stored file path
   * @throws IOException on storage failure
   */
  Path store(JdbcDriverDefinition driver, String sourceName, InputStream inputStream) throws IOException;

  /**
   * Resolves a previously stored artifact location.
   *
   * @param location stored location
   * @return resolved path
   */
  Path resolve(String location);
}
