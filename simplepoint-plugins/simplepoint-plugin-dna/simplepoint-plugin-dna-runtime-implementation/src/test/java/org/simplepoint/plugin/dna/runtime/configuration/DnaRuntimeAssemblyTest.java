package org.simplepoint.plugin.dna.runtime.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalValueConverter;
import org.simplepoint.plugin.dna.runtime.api.service.PhysicalBindingTargetResolver;
import org.simplepoint.plugin.dna.runtime.api.service.PhysicalModelService;
import org.simplepoint.plugin.dna.runtime.api.service.PhysicalTableNamingStrategy;
import org.simplepoint.plugin.dna.runtime.repository.DnaRuntimeAuditEventRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelBindingRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelRepository;
import org.simplepoint.plugin.dna.runtime.repository.PhysicalModelVersionRepository;
import org.simplepoint.plugin.dna.runtime.service.impl.PhysicalModelServiceImpl;
import org.simplepoint.plugin.dna.runtime.service.support.JpaDnaRuntimeAuditPublisher;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaCodec;
import org.simplepoint.plugin.dna.runtime.service.support.PhysicalSchemaValidator;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class DnaRuntimeAssemblyTest {

  @Test
  void assemblesReplaceableRuntimePortsAndPhysicalModelService() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
      context.registerBean(
          PhysicalModelRepository.class,
          () -> mock(PhysicalModelRepository.class)
      );
      context.registerBean(
          PhysicalModelBindingRepository.class,
          () -> mock(PhysicalModelBindingRepository.class)
      );
      context.registerBean(
          PhysicalModelVersionRepository.class,
          () -> mock(PhysicalModelVersionRepository.class)
      );
      context.registerBean(
          DnaRuntimeAuditEventRepository.class,
          () -> mock(DnaRuntimeAuditEventRepository.class)
      );
      context.register(
          DnaRuntimeConfiguration.class,
          StandardLogicalValueConverter.class,
          PhysicalSchemaCodec.class,
          PhysicalSchemaValidator.class,
          RuntimeRequestContext.class,
          JpaDnaRuntimeAuditPublisher.class,
          PhysicalModelServiceImpl.class
      );
      context.refresh();

      assertThat(context.getBean(PhysicalModelService.class)).isNotNull();
      assertThat(context.getBean(LogicalValueConverter.class)).isNotNull();
      assertThat(context.getBean(PhysicalBindingTargetResolver.class)).isNotNull();
      assertThat(context.getBean(PhysicalTableNamingStrategy.class)).isNotNull();
    }
  }
}
