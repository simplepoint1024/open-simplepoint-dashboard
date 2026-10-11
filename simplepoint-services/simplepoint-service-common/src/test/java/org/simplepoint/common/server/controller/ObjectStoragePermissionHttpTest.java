package org.simplepoint.common.server.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import org.junit.jupiter.api.*;
import org.simplepoint.core.*;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageObject;
import org.simplepoint.plugin.storage.api.properties.ObjectStorageProperties;
import org.simplepoint.plugin.storage.api.repository.*;
import org.simplepoint.plugin.storage.api.spi.ObjectStorageDriver;
import org.simplepoint.plugin.storage.rest.controller.ObjectStorageServiceController;
import org.simplepoint.plugin.storage.service.impl.ObjectStorageObjectServiceImpl;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.*;

/** Exercises the shared authenticated endpoint through the actual plugin handler mapping. */
class ObjectStoragePermissionHttpTest {
  AnnotationConfigWebApplicationContext web;
  MockMvc mvc;
  AuthorizationContext context;
  ObjectStorageObjectRepository repository = mock(ObjectStorageObjectRepository.class);
  ObjectStorageDriver driver = mock(ObjectStorageDriver.class);

  @BeforeEach void setup() {
    context = new AuthorizationContext(); context.setUserId("reader");
    context.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    context.setResourceDataScopes(Map.of("storage.objects.view", new ResourceDataScope("SELF", Set.of(), false)));
    var service = new ObjectStorageObjectServiceImpl(repository, mock(DetailsProviderService.class),
        mock(ObjectStorageTenantQuotaRepository.class), List.of(driver), new ObjectStorageProperties(), "app");
    web = new AnnotationConfigWebApplicationContext();
    web.setServletContext(new org.springframework.mock.web.MockServletContext());
    web.register(WebConfiguration.class);
    web.addBeanFactoryPostProcessor(beans -> beans.registerSingleton("storageService", service));
    web.refresh(); mvc = MockMvcBuilders.webAppContextSetup(web).build();
  }
  @AfterEach void clear() {
    if (web != null) web.close(); ResourcePolicyContext.clear();
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }
  @Test void sharedDetailAndDownloadCannotBypassTheAdminPagePolicy() throws Exception {
    var foreign = new ObjectStorageObject(); foreign.setId("foreign"); foreign.setCreatedBy("other");
    when(repository.findActiveByIdAndTenantId("foreign", "tenant-a")).thenReturn(Optional.of(foreign));
    mvc.perform(get("/object-storage/objects/foreign")
        .requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)).andExpect(status().isNotFound());
    var failure = assertThrows(jakarta.servlet.ServletException.class, () -> mvc.perform(
        get("/object-storage/objects/foreign/content").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context)));
    assertInstanceOf(AccessDeniedException.class, failure.getCause());
    verifyNoInteractions(driver); assertNull(ResourcePolicyContext.get());
  }
  @Test void ownMetadataUsesPageFieldPermissionsWithoutRequiringAnAdminMenuGrant() throws Exception {
    context.setResourceFieldPermissions(Map.of("storage.objects.view", Map.of(
        ObjectStorageObject.class.getName() + "#originalFileName", "HIDDEN")));
    var own = new ObjectStorageObject(); own.setId("own"); own.setCreatedBy("reader"); own.setOriginalFileName("private.txt");
    when(repository.findActiveByIdAndTenantId("own", "tenant-a")).thenReturn(Optional.of(own));
    mvc.perform(get("/object-storage/objects/own").requestAttr(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context))
        .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("own"))
        .andExpect(jsonPath("$.originalFileName").doesNotExist());
    assertNull(ResourcePolicyContext.get());
  }

  @Configuration(proxyBeanMethods = false) @EnableWebMvc
  static class WebConfiguration implements WebMvcConfigurer {
    @Bean ObjectStorageServiceController storageController(ObjectStorageObjectServiceImpl service) {
      return new ObjectStorageServiceController(service);
    }
    @Bean org.simplepoint.plugin.webmvc.handler.ServletMappingPluginInstanceHandler pluginMapping(
        org.springframework.context.ApplicationContext context) {
      var mapping = new org.simplepoint.plugin.webmvc.handler.ServletMappingPluginInstanceHandler(
          new org.simplepoint.plugin.spring.handle.SpringBeanPluginInstanceHandler(context));
      mapping.setOrder(-1); return mapping;
    }
    @Bean org.springframework.web.servlet.handler.MappedInterceptor resourcePermissions() throws Exception {
      var catalog = new org.simplepoint.security.oauth2.resourceserver.scope.ClasspathResourceScopeRegistry(new com.fasterxml.jackson.databind.ObjectMapper());
      return new org.simplepoint.security.oauth2.resourceserver.config.ResourceServerAutoConfiguration().resourceScopeInterceptor(catalog);
    }
    @Override public void configureMessageConverters(List<org.springframework.http.converter.HttpMessageConverter<?>> converters) {
      var mapper = tools.jackson.databind.json.JsonMapper.builder()
          .addModule(new org.simplepoint.core.jackson.FieldScopeJackson3Module()).build();
      converters.add(new org.springframework.http.converter.StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8));
      converters.add(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter(mapper));
    }
  }
}
