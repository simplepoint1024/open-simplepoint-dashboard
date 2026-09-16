package org.simplepoint.plugin.dna.core.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;
import org.simplepoint.plugin.dna.core.api.repository.JdbcDriverDefinitionRepository;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.simplepoint.plugin.dna.core.api.vo.JdbcDriverUploadRequest;
import org.simplepoint.plugin.dna.core.service.support.JdbcDriverArtifactManager;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class JdbcDriverDefinitionServiceImplTest {

  @Mock
  private JdbcDriverDefinitionRepository repository;

  @Mock
  private DetailsProviderService detailsProviderService;

  @Mock
  private JdbcDriverArtifactManager artifactManager;

  @Mock
  private JdbcDataSourceDefinitionService dataSourceService;

  @Test
  void createShouldAutoFillMetadataFromDriverJar() {
    when(artifactManager.downloadDraft(any(JdbcDriverDefinition.class))).thenAnswer(invocation -> {
      JdbcDriverDefinition driver = invocation.getArgument(0);
      driver.setDriverClassName("com.example.TestMysqlDriver");
      driver.setJdbcUrlPattern("^jdbc:mysql:.*$");
      driver.setVersion("8.4.0");
      driver.setLocalJarPath("/tmp/mysql-driver.jar");
      driver.setDownloadedAt(Instant.parse("2026-04-07T06:30:00Z"));
      driver.setLastDownloadMessage("下载成功，已自动识别驱动元数据");
      return driver;
    });
    when(repository.save(any(JdbcDriverDefinition.class))).thenAnswer(invocation -> invocation.getArgument(0));
    JdbcDriverDefinitionServiceImpl service = new JdbcDriverDefinitionServiceImpl(
        repository,
        detailsProviderService,
        artifactManager,
        dataSourceService
    );
    JdbcDriverDefinition driver = new JdbcDriverDefinition();
    driver.setName("MySQL");
    driver.setCode("mysql");
    driver.setDatabaseType("mysql");
    driver.setDownloadUrl("https://example.com/mysql-driver.jar");

    JdbcDriverDefinition created = service.create(driver);

    assertEquals("com.example.TestMysqlDriver", created.getDriverClassName());
    assertEquals("^jdbc:mysql:.*$", created.getJdbcUrlPattern());
    assertEquals("8.4.0", created.getVersion());
    assertNotNull(created.getDownloadedAt());
    verify(artifactManager).downloadDraft(driver);
  }

  @Test
  void createByUploadShouldAutoFillMetadataWithoutDownloadUrl() {
    when(artifactManager.uploadDraft(any(JdbcDriverDefinition.class), any())).thenAnswer(invocation -> {
      JdbcDriverDefinition driver = invocation.getArgument(0);
      driver.setDriverClassName("com.example.TestMysqlDriver");
      driver.setJdbcUrlPattern("^jdbc:mysql:.*$");
      driver.setVersion("8.4.0");
      driver.setLocalJarPath("/tmp/mysql-driver.jar");
      driver.setDownloadedAt(Instant.parse("2026-04-07T06:45:00Z"));
      driver.setLastDownloadMessage("上传成功，已自动识别驱动元数据");
      return driver;
    });
    when(repository.save(any(JdbcDriverDefinition.class))).thenAnswer(invocation -> invocation.getArgument(0));
    JdbcDriverDefinitionServiceImpl service = new JdbcDriverDefinitionServiceImpl(
        repository,
        detailsProviderService,
        artifactManager,
        dataSourceService
    );
    JdbcDriverUploadRequest request = new JdbcDriverUploadRequest();
    request.setName("MySQL");
    request.setCode("mysql");
    request.setDatabaseType("mysql");
    MockMultipartFile file = new MockMultipartFile("file", "mysql-driver.jar", "application/java-archive", new byte[]{1});

    JdbcDriverDefinition created = service.createByUpload(file, request);

    assertEquals("com.example.TestMysqlDriver", created.getDriverClassName());
    assertEquals("^jdbc:mysql:.*$", created.getJdbcUrlPattern());
    assertEquals("8.4.0", created.getVersion());
    assertEquals(Boolean.TRUE, created.getEnabled());
    verify(artifactManager).uploadDraft(any(JdbcDriverDefinition.class), any());
  }

  @Test
  void uploadShouldRefreshExistingDriverArtifactAndDisconnectDataSources() {
    JdbcDriverDefinition current = new JdbcDriverDefinition();
    current.setId("driver-1");
    current.setCode("mysql");
    current.setName("MySQL");
    current.setDatabaseType("mysql");
    when(repository.findActiveById("driver-1")).thenReturn(Optional.of(current));
    when(artifactManager.upload(any(JdbcDriverDefinition.class), any())).thenAnswer(invocation -> {
      JdbcDriverDefinition driver = invocation.getArgument(0);
      driver.setDriverClassName("com.example.TestMysqlDriver");
      driver.setJdbcUrlPattern("^jdbc:mysql:.*$");
      driver.setVersion("8.4.0");
      driver.setLocalJarPath("/tmp/mysql-driver.jar");
      driver.setDownloadedAt(Instant.parse("2026-04-07T06:50:00Z"));
      driver.setLastDownloadMessage("上传成功，已自动识别驱动元数据");
      return driver;
    });
    JdbcDriverDefinitionServiceImpl service = new JdbcDriverDefinitionServiceImpl(
        repository,
        detailsProviderService,
        artifactManager,
        dataSourceService
    );
    MockMultipartFile file = new MockMultipartFile("file", "mysql-driver.jar", "application/java-archive", new byte[]{1});

    JdbcDriverDefinition uploaded = service.upload("driver-1", file);

    assertEquals("com.example.TestMysqlDriver", uploaded.getDriverClassName());
    assertEquals("^jdbc:mysql:.*$", uploaded.getJdbcUrlPattern());
    verify(dataSourceService).disconnectByDriverId("driver-1");
  }
}
