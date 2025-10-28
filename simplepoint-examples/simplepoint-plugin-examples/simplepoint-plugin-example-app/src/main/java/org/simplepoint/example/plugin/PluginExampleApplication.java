package org.simplepoint.example.plugin;

import java.net.URL;
import org.simplepoint.core.ApplicationClassLoader;
import org.simplepoint.core.ApplicationContextHolder;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main application class for the Plugin Example application.
 * This class serves as the entry point for the Spring Boot application.
 */
@SpringBootApplication(scanBasePackages = {"org.simplepoint.**"})
public class PluginExampleApplication {
  /**
   * The main method to run the Plugin Example application.
   *
   * @param args command-line arguments
   */
  public static void main(String[] args) {
    ApplicationContextHolder.setClassloader(
        new ApplicationClassLoader(new URL[] {}, Thread.currentThread().getContextClassLoader()));
    Thread.currentThread().setContextClassLoader(ApplicationContextHolder.getClassloader());
    org.springframework.boot.SpringApplication.run(PluginExampleApplication.class, args);
  }
}
