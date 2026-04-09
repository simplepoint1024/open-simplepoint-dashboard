package org.simplepoint.plugin.dna.core.service.support;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.file.Path;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import org.simplepoint.core.ApplicationClassLoader;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;
import org.springframework.stereotype.Component;

/**
 * Resolves JDBC driver metadata directly from a driver artifact.
 */
@Component
public class JdbcDriverArtifactMetadataResolver {

  private static final String GENERIC_JDBC_URL_PATTERN = "^jdbc:[^\\s]+$";

  private static final List<JdbcUrlProbe> JDBC_URL_PROBES = List.of(
      new JdbcUrlProbe("mysql", "jdbc:mysql:.*", "jdbc:mysql://localhost:3306/demo"),
      new JdbcUrlProbe("mariadb", "jdbc:mariadb:.*", "jdbc:mariadb://localhost:3306/demo"),
      new JdbcUrlProbe("postgresql", "jdbc:postgresql:.*", "jdbc:postgresql://localhost:5432/demo"),
      new JdbcUrlProbe("oracle", "jdbc:oracle:.*", "jdbc:oracle:thin:@//localhost:1521/XEPDB1"),
      new JdbcUrlProbe("sqlserver", "jdbc:sqlserver:.*", "jdbc:sqlserver://localhost:1433;databaseName=demo"),
      new JdbcUrlProbe("db2", "jdbc:db2:.*", "jdbc:db2://localhost:50000/demo"),
      new JdbcUrlProbe("sqlite", "jdbc:sqlite:.*", "jdbc:sqlite:/tmp/demo.db"),
      new JdbcUrlProbe("h2", "jdbc:h2:.*", "jdbc:h2:mem:demo"),
      new JdbcUrlProbe("clickhouse", "jdbc:clickhouse:.*", "jdbc:clickhouse://localhost:8123/default"),
      new JdbcUrlProbe("dm", "jdbc:dm:.*", "jdbc:dm://localhost:5236"),
      new JdbcUrlProbe("kingbase", "jdbc:kingbase8:.*", "jdbc:kingbase8://localhost:54321/demo"),
      new JdbcUrlProbe("gaussdb", "jdbc:gaussdb:.*", "jdbc:gaussdb://localhost:8000/demo"),
      new JdbcUrlProbe("oceanbase", "jdbc:oceanbase:.*", "jdbc:oceanbase://localhost:2881/demo"),
      new JdbcUrlProbe("trino", "jdbc:trino:.*", "jdbc:trino://localhost:8080/catalog/schema"),
      new JdbcUrlProbe("presto", "jdbc:presto:.*", "jdbc:presto://localhost:8080/catalog/schema"),
      new JdbcUrlProbe("phoenix", "jdbc:phoenix:.*", "jdbc:phoenix:localhost:2181:/hbase")
  );

  /**
   * Resolves metadata from the given JDBC driver artifact.
   *
   * @param artifactPath local JDBC driver jar path
   * @param driver       current driver definition, used as a selection hint
   * @return resolved driver metadata
   */
  public JdbcDriverArtifactMetadata resolve(final Path artifactPath, final JdbcDriverDefinition driver) {
    if (artifactPath == null) {
      throw new IllegalArgumentException("驱动文件路径不能为空");
    }
    try (ApplicationClassLoader classLoader = new ApplicationClassLoader(
        new URL[]{artifactPath.toUri().toURL()},
        resolveParentClassLoader()
    )) {
      List<DriverCandidate> candidates = discoverDriverCandidates(artifactPath, classLoader);
      if (candidates.isEmpty()) {
        throw new IllegalStateException("未能从驱动 JAR 中识别 JDBC Driver 实现");
      }
      DriverCandidate candidate = selectCandidate(candidates, driver);
      return new JdbcDriverArtifactMetadata(
          candidate.className(),
          resolveJdbcUrlPattern(candidate.supportedProbes()),
          resolveVersion(candidate.driver(), artifactPath, driver)
      );
    } catch (IOException ex) {
      throw new IllegalStateException("解析驱动 JAR 失败: " + artifactPath, ex);
    }
  }

  private static List<DriverCandidate> discoverDriverCandidates(
      final Path artifactPath,
      final ApplicationClassLoader classLoader
  ) {
    LinkedHashMap<String, DriverCandidate> candidates = new LinkedHashMap<>();
    ServiceLoader<Driver> serviceLoader = ServiceLoader.load(Driver.class, classLoader);
    serviceLoader.stream().forEach(provider -> {
      DriverCandidate candidate = instantiateProvider(provider);
      if (candidate != null) {
        candidates.putIfAbsent(candidate.className(), candidate);
      }
    });
    if (!candidates.isEmpty()) {
      return List.copyOf(candidates.values());
    }
    try (JarFile jarFile = new JarFile(artifactPath.toFile())) {
      jarFile.stream()
          .map(entry -> entry.getName())
          .filter(name -> name.endsWith(".class"))
          .filter(name -> !name.startsWith("META-INF/"))
          .map(JdbcDriverArtifactMetadataResolver::toClassName)
          .map(className -> instantiateClass(className, classLoader))
          .filter(candidate -> candidate != null)
          .forEach(candidate -> candidates.putIfAbsent(candidate.className(), candidate));
      return List.copyOf(candidates.values());
    } catch (IOException ex) {
      throw new IllegalStateException("读取驱动 JAR 失败: " + artifactPath, ex);
    }
  }

  private static DriverCandidate instantiateProvider(final ServiceLoader.Provider<Driver> provider) {
    try {
      return toCandidate(provider.get());
    } catch (ServiceConfigurationError ex) {
      return null;
    }
  }

  private static DriverCandidate instantiateClass(final String className, final ClassLoader classLoader) {
    try {
      Class<?> candidateClass = Class.forName(className, false, classLoader);
      if (!Driver.class.isAssignableFrom(candidateClass)
          || candidateClass.isInterface()
          || Modifier.isAbstract(candidateClass.getModifiers())) {
        return null;
      }
      Driver driver = (Driver) candidateClass.getDeclaredConstructor().newInstance();
      return toCandidate(driver);
    } catch (ReflectiveOperationException | LinkageError ex) {
      return null;
    }
  }

  private static DriverCandidate toCandidate(final Driver driver) {
    return new DriverCandidate(
        driver.getClass().getName(),
        driver,
        resolveSupportedProbes(driver)
    );
  }

  private static List<JdbcUrlProbe> resolveSupportedProbes(final Driver driver) {
    List<JdbcUrlProbe> matched = new ArrayList<>();
    for (JdbcUrlProbe probe : JDBC_URL_PROBES) {
      try {
        if (driver.acceptsURL(probe.sampleUrl())) {
          matched.add(probe);
        }
      } catch (SQLException ex) {
        // Ignore probe failures and keep trying other known URL patterns.
      }
    }
    return List.copyOf(matched);
  }

  private static DriverCandidate selectCandidate(
      final List<DriverCandidate> candidates,
      final JdbcDriverDefinition driver
  ) {
    String explicitDriverClassName = trimToNull(driver == null ? null : driver.getDriverClassName());
    if (explicitDriverClassName != null) {
      return candidates.stream()
          .filter(candidate -> explicitDriverClassName.equals(candidate.className()))
          .findFirst()
          .orElse(candidates.get(0));
    }
    String preferredType = normalizeDatabaseType(firstNonBlank(
        driver == null ? null : driver.getDatabaseType(),
        driver == null ? null : driver.getCode()
    ));
    if (preferredType == null) {
      return candidates.get(0);
    }
    return candidates.stream()
        .filter(candidate -> candidate.matches(preferredType))
        .findFirst()
        .orElse(candidates.get(0));
  }

  private static String resolveJdbcUrlPattern(final List<JdbcUrlProbe> probes) {
    if (probes == null || probes.isEmpty()) {
      return GENERIC_JDBC_URL_PATTERN;
    }
    LinkedHashSet<String> bodies = probes.stream()
        .map(JdbcUrlProbe::patternBody)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    if (bodies.size() == 1) {
      return '^' + bodies.iterator().next() + '$';
    }
    return "^(?:" + String.join("|", bodies) + ")$";
  }

  private static String resolveVersion(
      final Driver driver,
      final Path artifactPath,
      final JdbcDriverDefinition definition
  ) {
    Package driverPackage = driver.getClass().getPackage();
    String packageVersion = trimToNull(driverPackage == null ? null : driverPackage.getImplementationVersion());
    if (packageVersion != null) {
      return packageVersion;
    }
    String pomVersion = resolvePomVersion(artifactPath);
    if (pomVersion != null) {
      return pomVersion;
    }
    int major = driver.getMajorVersion();
    int minor = driver.getMinorVersion();
    if (major > 0 || minor > 0) {
      return minor >= 0 ? major + "." + minor : Integer.toString(major);
    }
    return trimToNull(definition == null ? null : definition.getVersion());
  }

  private static String resolvePomVersion(final Path artifactPath) {
    try (JarFile jarFile = new JarFile(artifactPath.toFile())) {
      return jarFile.stream()
          .map(entry -> entry.getName())
          .filter(name -> name.endsWith("/pom.properties"))
          .map(name -> readPomVersion(jarFile, name))
          .filter(version -> version != null && !version.isBlank())
          .findFirst()
          .orElse(null);
    } catch (IOException ex) {
      return null;
    }
  }

  private static String readPomVersion(final JarFile jarFile, final String entryName) {
    try (InputStream inputStream = jarFile.getInputStream(jarFile.getJarEntry(entryName))) {
      Properties properties = new Properties();
      properties.load(inputStream);
      return trimToNull(properties.getProperty("version"));
    } catch (IOException ex) {
      return null;
    }
  }

  private static ClassLoader resolveParentClassLoader() {
    ClassLoader context = Thread.currentThread().getContextClassLoader();
    return context == null ? JdbcDriverArtifactMetadataResolver.class.getClassLoader() : context;
  }

  private static String toClassName(final String entryName) {
    return entryName.substring(0, entryName.length() - ".class".length()).replace('/', '.');
  }

  private static String normalizeDatabaseType(final String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return null;
    }
    String lower = normalized.toLowerCase();
    if (lower.contains("postgresql") || lower.contains("postgres")) {
      return "postgresql";
    }
    if (lower.contains("mariadb")) {
      return "mariadb";
    }
    if (lower.contains("mysql")) {
      return "mysql";
    }
    if (lower.contains("oracle")) {
      return "oracle";
    }
    if (lower.contains("sqlserver") || lower.contains("mssql")) {
      return "sqlserver";
    }
    if (lower.contains("db2")) {
      return "db2";
    }
    if (lower.contains("sqlite")) {
      return "sqlite";
    }
    if (lower.contains("clickhouse")) {
      return "clickhouse";
    }
    if (lower.contains("kingbase")) {
      return "kingbase";
    }
    if (lower.contains("gaussdb")) {
      return "gaussdb";
    }
    if (lower.contains("oceanbase")) {
      return "oceanbase";
    }
    if (lower.contains("trino")) {
      return "trino";
    }
    if (lower.contains("presto")) {
      return "presto";
    }
    if (lower.contains("phoenix")) {
      return "phoenix";
    }
    if (lower.contains("dameng") || lower.contains("dm.jdbc") || "dm".equals(lower)) {
      return "dm";
    }
    if (lower.contains("h2")) {
      return "h2";
    }
    return lower;
  }

  private static String firstNonBlank(final String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      String normalized = trimToNull(value);
      if (normalized != null) {
        return normalized;
      }
    }
    return null;
  }

  private static String trimToNull(final String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  record JdbcDriverArtifactMetadata(
      String driverClassName,
      String jdbcUrlPattern,
      String version
  ) {
  }

  private record JdbcUrlProbe(
      String databaseType,
      String patternBody,
      String sampleUrl
  ) {
  }

  private record DriverCandidate(
      String className,
      Driver driver,
      List<JdbcUrlProbe> supportedProbes
  ) {

    private boolean matches(final String databaseType) {
      if (databaseType == null) {
        return false;
      }
      boolean matchedByProbe = supportedProbes.stream()
          .map(JdbcUrlProbe::databaseType)
          .anyMatch(databaseType::equals);
      return matchedByProbe || normalizeDatabaseType(className).contains(databaseType);
    }
  }
}
