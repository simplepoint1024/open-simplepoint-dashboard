package org.simplepoint.plugin.dna.core.api.service;

import java.util.List;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataRequests;
import org.springframework.data.domain.Pageable;

/**
 * Service contract for runtime JDBC metadata browsing and paged data preview.
 */
public interface JdbcMetadataBrowserService {

  /**
   * Lists metadata tree children for the supplied datasource and parent path.
   *
   * @param dataSourceId datasource id
   * @param request parent path request
   * @return child tree nodes
   */
  List<JdbcMetadataModels.TreeNode> children(String dataSourceId, JdbcMetadataRequests.PathRequest request);

  /**
   * Returns detailed table/view structure information.
   *
   * @param dataSourceId datasource id
   * @param request table path request
   * @return structure details
   */
  JdbcMetadataModels.TableStructure structure(String dataSourceId, JdbcMetadataRequests.PathRequest request);

  /**
   * Returns paged table/view data preview.
   *
   * @param dataSourceId datasource id
   * @param request table path request
   * @param pageable paging arguments
   * @return preview page
   */
  JdbcMetadataModels.DataPreviewPage preview(
      String dataSourceId,
      JdbcMetadataRequests.PathRequest request,
      Pageable pageable
  );
}
