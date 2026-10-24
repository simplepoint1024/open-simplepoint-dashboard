package org.simplepoint.standalone;

import org.simplepoint.data.jpa.composition.EnableModulePersistence;
import org.simplepoint.platform.composition.spring.EnablePlatformModules;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/** One servlet process using the same selected domain implementations and owned boundaries. */
@SpringBootApplication(scanBasePackageClasses = StandaloneApplication.class)
@EnablePlatformModules({"common-ui", "authorization-http", "bootstrap-registry", "app-http",
    "dna-http", "workflow-http", "auditing-http", "ai-http", "mcp-gateway"})
@EnableModulePersistence
@EnableMethodSecurity
@org.springframework.context.annotation.Import({
    org.simplepoint.security.core.configuration.SecurityAutoConfiguration.class,
    org.simplepoint.security.authentication.AuthenticationSecretCipher.class,
    org.simplepoint.core.ApplicationContextProvider.class,
    org.simplepoint.core.security.SpringBeanDetailsProviderService.class
})
@EnableCaching
@EnableSpringDataWebSupport(pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
public class StandaloneApplication {
  /** Starts only this JVM; no legacy service launchers or child processes. */
  public static void main(String[] args) {
    SpringApplication.run(StandaloneApplication.class, args);
  }
}
