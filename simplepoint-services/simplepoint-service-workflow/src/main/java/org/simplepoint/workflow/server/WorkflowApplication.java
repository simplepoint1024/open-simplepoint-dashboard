package org.simplepoint.workflow.server;

import com.simplepoint.service.router.annotation.EnableServiceRouter;
import org.simplepoint.boot.starter.Boot;
import org.simplepoint.data.jpa.base.repository.EnableRepository;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/** Independent Flowable process center service. */
@Boot
@EnableCaching
@EnableScheduling
@EnableRepository
@EnableMethodSecurity
@EnableServiceRouter(basePackages = "org.simplepoint")
@EnableSpringDataWebSupport(pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
public class WorkflowApplication {

  /** Starts the process center. */
  public static void main(final String[] args) {
    org.simplepoint.boot.starter.Application.run(WorkflowApplication.class, args);
  }
}
