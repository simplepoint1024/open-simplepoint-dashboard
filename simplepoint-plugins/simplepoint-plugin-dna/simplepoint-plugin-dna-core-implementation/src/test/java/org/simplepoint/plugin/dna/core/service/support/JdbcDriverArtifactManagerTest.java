package org.simplepoint.plugin.dna.core.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;
import org.simplepoint.plugin.dna.core.api.properties.DnaProperties;
import org.simplepoint.plugin.dna.core.api.repository.JdbcDriverDefinitionRepository;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class JdbcDriverArtifactManagerTest {

  @Mock
  private JdbcDriverDefinitionRepository repository;

  @TempDir
  Path tempDir;

  @Test
  void downloadShouldPersistRemoteJarToConfiguredStorage() throws Exception {
    byte[] artifactBytes = createMysqlDriverJar();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/mysql-driver.jar", new StaticBodyHandler(artifactBytes));
    server.start();
    try {
      DnaProperties properties = new DnaProperties();
      properties.setDriverStoragePath(tempDir.toString());
      JdbcDriverDefinition driver = new JdbcDriverDefinition();
      driver.setId("driver-1");
      driver.setCode("mysql");
      driver.setName("MySQL");
      driver.setVersion("8.4.0");
      driver.setDownloadUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/mysql-driver.jar");
      when(repository.findActiveById("driver-1")).thenReturn(Optional.of(driver));
      when(repository.save(any(JdbcDriverDefinition.class))).thenAnswer(invocation -> invocation.getArgument(0));
      JdbcDriverArtifactManager manager = new JdbcDriverArtifactManager(
          repository,
          List.of(new HttpJdbcDriverArtifactDownloader()),
          new FileSystemJdbcDriverArtifactStorage(properties),
          new JdbcDriverArtifactMetadataResolver(),
          properties
      );

      JdbcDriverDefinition downloaded = manager.download(driver);

      assertNotNull(downloaded.getDownloadedAt());
      assertNotNull(downloaded.getLocalJarPath());
      assertTrue(Files.exists(Path.of(downloaded.getLocalJarPath())));
      assertEquals("com.example.TestMysqlDriver", downloaded.getDriverClassName());
      assertEquals("^jdbc:mysql:.*$", downloaded.getJdbcUrlPattern());
      assertEquals("8.4.0", downloaded.getVersion());
      assertTrue(downloaded.getLastDownloadMessage().contains("自动识别"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void uploadDraftShouldPersistUploadedJarToConfiguredStorage() throws Exception {
    DnaProperties properties = new DnaProperties();
    properties.setDriverStoragePath(tempDir.toString());
    JdbcDriverDefinition driver = new JdbcDriverDefinition();
    driver.setCode("mysql");
    driver.setName("MySQL");
    JdbcDriverArtifactManager manager = new JdbcDriverArtifactManager(
        repository,
        List.of(),
        new FileSystemJdbcDriverArtifactStorage(properties),
        new JdbcDriverArtifactMetadataResolver(),
        properties
    );
    MockMultipartFile file = new MockMultipartFile(
        "file",
        "mysql-driver.jar",
        "application/java-archive",
        createMysqlDriverJar()
    );

    JdbcDriverDefinition uploaded = manager.uploadDraft(driver, file);

    assertNotNull(uploaded.getDownloadedAt());
    assertNotNull(uploaded.getLocalJarPath());
    assertTrue(Files.exists(Path.of(uploaded.getLocalJarPath())));
    assertEquals("com.example.TestMysqlDriver", uploaded.getDriverClassName());
    assertEquals("^jdbc:mysql:.*$", uploaded.getJdbcUrlPattern());
    assertEquals("8.4.0", uploaded.getVersion());
    assertTrue(uploaded.getLastDownloadMessage().contains("上传成功"));
  }

  private byte[] createMysqlDriverJar() throws IOException {
    return createJdbcDriverJar(
        "com.example.TestMysqlDriver",
        """
            package com.example;

            import java.sql.Connection;
            import java.sql.Driver;
            import java.sql.DriverPropertyInfo;
            import java.sql.SQLException;
            import java.util.Properties;
            import java.util.logging.Logger;

            public final class TestMysqlDriver implements Driver {
              @Override
              public Connection connect(String url, Properties info) {
                return null;
              }

              @Override
              public boolean acceptsURL(String url) {
                return url != null && url.startsWith("jdbc:mysql:");
              }

              @Override
              public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
                return new DriverPropertyInfo[0];
              }

              @Override
              public int getMajorVersion() {
                return 8;
              }

              @Override
              public int getMinorVersion() {
                return 4;
              }

              @Override
              public boolean jdbcCompliant() {
                return false;
              }

              @Override
              public Logger getParentLogger() {
                return Logger.getGlobal();
              }
            }
            """,
        Map.of(
            "META-INF/services/java.sql.Driver", "com.example.TestMysqlDriver\n",
            "META-INF/maven/com.example/mysql-driver/pom.properties",
            "groupId=com.example\nartifactId=mysql-driver\nversion=8.4.0\n"
        )
    );
  }

  private byte[] createJdbcDriverJar(
      final String className,
      final String source,
      final Map<String, String> resources
  ) throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException("当前 JDK 不支持编译测试驱动");
    }
    Path sourceDir = tempDir.resolve("src");
    Path outputDir = tempDir.resolve("classes");
    Files.createDirectories(sourceDir);
    Files.createDirectories(outputDir);
    String relativeSourcePath = className.replace('.', '/') + ".java";
    Path sourcePath = sourceDir.resolve(relativeSourcePath);
    Files.createDirectories(sourcePath.getParent());
    Files.writeString(sourcePath, source);
    int compileStatus = compiler.run(null, null, null, "-d", outputDir.toString(), sourcePath.toString());
    if (compileStatus != 0) {
      throw new IllegalStateException("测试驱动编译失败: " + compileStatus);
    }
    Path jarPath = tempDir.resolve("driver.jar");
    try (OutputStream outputStream = Files.newOutputStream(jarPath);
         JarOutputStream jarOutputStream = new JarOutputStream(outputStream)) {
      try (var paths = Files.walk(outputDir)) {
        paths.filter(Files::isRegularFile)
            .forEach(path -> writeJarEntry(jarOutputStream, outputDir, path));
      }
      for (Map.Entry<String, String> resource : resources.entrySet()) {
        writeJarEntry(jarOutputStream, resource.getKey(), resource.getValue().getBytes());
      }
    }
    return Files.readAllBytes(jarPath);
  }

  private static void writeJarEntry(
      final JarOutputStream jarOutputStream,
      final Path outputDir,
      final Path sourcePath
  ) {
    try {
      String entryName = outputDir.relativize(sourcePath).toString().replace('\\', '/');
      writeJarEntry(jarOutputStream, entryName, Files.readAllBytes(sourcePath));
    } catch (IOException ex) {
      throw new IllegalStateException("写入测试驱动 JAR 失败", ex);
    }
  }

  private static void writeJarEntry(
      final JarOutputStream jarOutputStream,
      final String entryName,
      final byte[] bytes
  ) {
    try {
      jarOutputStream.putNextEntry(new JarEntry(entryName));
      jarOutputStream.write(bytes);
      jarOutputStream.closeEntry();
    } catch (IOException ex) {
      throw new IllegalStateException("写入测试驱动 JAR 失败", ex);
    }
  }

  private record StaticBodyHandler(byte[] body) implements HttpHandler {
    @Override
    public void handle(final HttpExchange exchange) throws IOException {
      exchange.sendResponseHeaders(200, body.length);
      try (var outputStream = exchange.getResponseBody()) {
        outputStream.write(body);
      }
    }
  }
}
