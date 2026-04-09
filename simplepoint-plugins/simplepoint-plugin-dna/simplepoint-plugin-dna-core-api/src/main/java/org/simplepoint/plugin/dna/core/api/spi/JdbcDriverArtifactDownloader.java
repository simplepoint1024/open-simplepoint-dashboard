package org.simplepoint.plugin.dna.core.api.spi;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;

/**
 * SPI for downloading JDBC driver artifacts.
 */
public interface JdbcDriverArtifactDownloader {

  /**
   * Whether the downloader supports the supplied remote URI.
   *
   * @param downloadUri remote artifact URI
   * @return true when supported
   */
  boolean supports(URI downloadUri);

  /**
   * Downloads the remote driver artifact as a stream.
   *
   * @param driver      driver definition
   * @param downloadUri remote artifact URI
   * @return artifact stream
   * @throws IOException          on I/O failure
   * @throws InterruptedException on interruption
   */
  InputStream download(JdbcDriverDefinition driver, URI downloadUri) throws IOException, InterruptedException;
}
