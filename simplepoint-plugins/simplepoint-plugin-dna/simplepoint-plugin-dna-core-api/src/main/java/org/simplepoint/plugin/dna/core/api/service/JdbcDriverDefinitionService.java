package org.simplepoint.plugin.dna.core.api.service;

import java.util.Optional;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;
import org.simplepoint.plugin.dna.core.api.vo.JdbcDriverDownloadResult;
import org.simplepoint.plugin.dna.core.api.vo.JdbcDriverUploadRequest;
import org.springframework.web.multipart.MultipartFile;

/**
 * Service contract for JDBC driver definitions.
 */
public interface JdbcDriverDefinitionService extends BaseService<JdbcDriverDefinition, String> {

  /**
   * Finds an active driver definition by id.
   *
   * @param id driver id
   * @return active driver definition
   */
  Optional<JdbcDriverDefinition> findActiveById(String id);

  /**
   * Finds an active driver definition by business code.
   *
   * @param code driver code
   * @return active driver definition
   */
  Optional<JdbcDriverDefinition> findActiveByCode(String code);

  /**
   * Creates a driver definition from an uploaded JDBC driver jar.
   *
   * @param file    uploaded driver jar
   * @param request driver definition request
   * @return created driver definition
   */
  JdbcDriverDefinition createByUpload(MultipartFile file, JdbcDriverUploadRequest request);

  /**
   * Downloads the configured JDBC driver artifact.
   *
   * @param id driver id
   * @return download result
   */
  JdbcDriverDownloadResult download(String id);

  /**
   * Replaces the local driver jar for an existing driver definition.
   *
   * @param id   driver id
   * @param file uploaded driver jar
   * @return updated driver definition
   */
  JdbcDriverDefinition upload(String id, MultipartFile file);
}
