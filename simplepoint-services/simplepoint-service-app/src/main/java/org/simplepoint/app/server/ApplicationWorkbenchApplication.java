package org.simplepoint.app.server;

import com.simplepoint.service.router.annotation.EnableServiceRouter;
import org.simplepoint.boot.starter.Boot;
import org.simplepoint.data.jpa.base.repository.EnableRepository;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/** Application workbench service entry point. */
@Boot
@org.springframework.scheduling.annotation.EnableScheduling
@EnableCaching
@EnableRepository
@EnableMethodSecurity
@EnableServiceRouter(basePackages = "org.simplepoint")
@EnableSpringDataWebSupport(pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
public class ApplicationWorkbenchApplication {

  /** Starts the application workbench service. */
  public static void main(String[] args) {
    org.simplepoint.boot.starter.Application.run(ApplicationWorkbenchApplication.class, args);
  }
}
