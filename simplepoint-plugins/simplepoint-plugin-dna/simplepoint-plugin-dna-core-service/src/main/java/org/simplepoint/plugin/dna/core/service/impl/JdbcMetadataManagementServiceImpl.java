package org.simplepoint.plugin.dna.core.service.impl;

import java.util.List;
import org.simplepoint.plugin.dna.core.api.service.JdbcDdlOperationService;
import org.simplepoint.plugin.dna.core.api.service.JdbcMetadataBrowserService;
import org.simplepoint.plugin.dna.core.api.service.JdbcMetadataManagementService;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataRequests;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Facade that delegates JDBC metadata browsing, preview, and DDL operations to
 * specialized service implementations.
 */
@Service
public class JdbcMetadataManagementServiceImpl implements JdbcMetadataManagementService {

  private final JdbcMetadataBrowserService browserService;

  private final JdbcDdlOperationService ddlOperationService;

  /**
   * Creates the metadata management facade.
   *
   * @param browserService browser service
   * @param ddlOperationService DDL operation service
   */
  public JdbcMetadataManagementServiceImpl(
      final JdbcMetadataBrowserService browserService,
      final JdbcDdlOperationService ddlOperationService
  ) {
    this.browserService = browserService;
    this.ddlOperationService = ddlOperationService;
  }

  /** {@inheritDoc} */
  @Override
  public List<JdbcMetadataModels.TreeNode> children(
      final String dataSourceId,
      final JdbcMetadataRequests.PathRequest request
  ) {
    return browserService.children(dataSourceId, request);
  }

  /** {@inheritDoc} */
  @Override
  public JdbcMetadataModels.TableStructure structure(
      final String dataSourceId,
      final JdbcMetadataRequests.PathRequest request
  ) {
    return browserService.structure(dataSourceId, request);
  }

  /** {@inheritDoc} */
  @Override
  public JdbcMetadataModels.DataPreviewPage preview(
      final String dataSourceId,
      final JdbcMetadataRequests.PathRequest request,
      final Pageable pageable
  ) {
    return browserService.preview(dataSourceId, request, pageable);
  }

  /** {@inheritDoc} */
  @Override
  public void createNamespace(
      final String dataSourceId,
      final JdbcMetadataRequests.NamespaceCreateRequest request
  ) {
    ddlOperationService.createNamespace(dataSourceId, request);
  }

  /** {@inheritDoc} */
  @Override
  public void drop(final String dataSourceId, final JdbcMetadataRequests.DropRequest request) {
    ddlOperationService.drop(dataSourceId, request);
  }

  /** {@inheritDoc} */
  @Override
  public void createTable(
      final String dataSourceId,
      final JdbcMetadataRequests.TableCreateRequest request
  ) {
    ddlOperationService.createTable(dataSourceId, request);
  }

  /** {@inheritDoc} */
  @Override
  public void createView(
      final String dataSourceId,
      final JdbcMetadataRequests.ViewCreateRequest request
  ) {
    ddlOperationService.createView(dataSourceId, request);
  }

  /** {@inheritDoc} */
  @Override
  public void addColumn(
      final String dataSourceId,
      final JdbcMetadataRequests.ColumnAddRequest request
  ) {
    ddlOperationService.addColumn(dataSourceId, request);
  }

  /** {@inheritDoc} */
  @Override
  public void alterColumn(
      final String dataSourceId,
      final JdbcMetadataRequests.ColumnAlterRequest request
  ) {
    ddlOperationService.alterColumn(dataSourceId, request);
  }

  /** {@inheritDoc} */
  @Override
  public void dropColumn(
      final String dataSourceId,
      final JdbcMetadataRequests.ColumnDropRequest request
  ) {
    ddlOperationService.dropColumn(dataSourceId, request);
  }

  /** {@inheritDoc} */
  @Override
  public void addConstraint(
      final String dataSourceId,
      final JdbcMetadataRequests.ConstraintAddRequest request
  ) {
    ddlOperationService.addConstraint(dataSourceId, request);
  }

  /** {@inheritDoc} */
  @Override
  public void dropConstraint(
      final String dataSourceId,
      final JdbcMetadataRequests.ConstraintDropRequest request
  ) {
    ddlOperationService.dropConstraint(dataSourceId, request);
  }
}
