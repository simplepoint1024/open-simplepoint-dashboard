package org.simplepoint.data.jpa.base.repository;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.annotation.AliasFor;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.data.repository.config.BootstrapMode;
import org.springframework.data.repository.query.QueryLookupStrategy;
import org.springframework.data.web.config.EnableSpringDataWebSupport;

/**
 * A meta-annotation to enable repository-related configurations in a Spring application.
 * This annotation combines multiple functionalities, such as enabling JPA repositories,
 * auditing, and Spring Data web support.
 *
 * <p>It allows for customization of JPA repository settings, including base packages, filters,
 * query lookup strategies, and transaction management. Additionally, it facilitates serialization
 * for Spring Data web integrations.
 * </p>
 */
@Documented
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@EnableJpaRepositories
@EnableJpaAuditing
@EntityScan
@EnableSpringDataWebSupport
public @interface EnableRepository {

  /**
   * Defines the base packages to scan for entity classes.
   * This is an alias for the {@code value} attribute in {@link EntityScan}.
   *
   * @return an array of base package names to scan for entities
   */
  @AliasFor(annotation = EntityScan.class, attribute = "value")
  String[] entityBasePackages() default {"org.simplepoint.**.entity"};

  /**
   * Defines the base package classes to scan for entity classes.
   * This is an alias for the {@code basePackageClasses} attribute in {@link EntityScan}.
   *
   * @return an array of base package classes to scan for entities
   */
  @AliasFor(annotation = EntityScan.class, attribute = "basePackageClasses")
  Class<?>[] entityBasePackageClasses() default {};

  /**
   * Alias for the 'value' attribute in {@link EnableJpaRepositories}.
   * Specifies the base packages to scan for JPA repositories.
   *
   * @return an array of base package names
   */
  @AliasFor(annotation = EnableJpaRepositories.class, attribute = "value")
  String[] value() default {};

  /**
   * Alias for the 'basePackages' attribute in {@link EnableJpaRepositories}.
   * Specifies the packages to scan for annotated components.
   *
   * @return an array of base package names
   */
  @AliasFor(annotation = EnableJpaRepositories.class, attribute = "basePackages")
  String[] basePackages() default {"org.simplepoint.**.repository"};

  /**
   * Alias for the 'basePackageClasses' attribute in {@link EnableJpaRepositories}.
   * Specifies the classes that define base packages for scanning.
   *
   * @return an array of base package classes
   */
  @AliasFor(annotation = EnableJpaRepositories.class, attribute = "basePackageClasses")
  Class<?>[] basePackageClasses() default {};

  /**
   * Alias for the 'includeFilters' attribute in {@link EnableJpaRepositories}.
   * Defines filters to include certain components during scanning.
   *
   * @return an array of include filters
   */
  @AliasFor(annotation = EnableJpaRepositories.class, attribute = "includeFilters")
  ComponentScan.Filter[] includeFilters() default {};

  /**
   * Alias for the 'excludeFilters' attribute in {@link EnableJpaRepositories}.
   * Defines filters to exclude certain components during scanning.
   *
   * @return an array of exclude filters
   */
  @AliasFor(annotation = EnableJpaRepositories.class, attribute = "excludeFilters")
  ComponentScan.Filter[] excludeFilters() default {};

  /**
   * Alias for the 'repositoryImplementationPostfix' attribute in {@link EnableJpaRepositories}.
   * Specifies the postfix for custom repository implementations.
   *
   * @return the repository implementation postfix
   */
  @AliasFor(annotation = EnableJpaRepositories.class, attribute = "repositoryImplementationPostfix")
  String repositoryImplementationPostfix() default "Impl";

  /**
   * Alias for the 'namedQueriesLocation' attribute in {@link EnableJpaRepositories}.
   * Specifies the location of named query files.
   *
   * @return the named queries location
   */
  @AliasFor(annotation = EnableJpaRepositories.class, attribute = "namedQueriesLocation")
  String namedQueriesLocation() default "";

  /**
   * Alias for the 'queryLookupStrategy' attribute in {@link EnableJpaRepositories}.
   * Specifies the query lookup strategy.
   *
   * @return the query lookup strategy
   */
  @AliasFor(annotation = EnableJpaRepositories.class, attribute = "queryLookupStrategy")
  QueryLookupStrategy.Key queryLookupStrategy() default QueryLookupStrategy.Key.CREATE_IF_NOT_FOUND;

  /**
   * Alias for the 'repositoryFactoryBeanClass' attribute in {@link EnableJpaRepositories}.
   * Specifies the class of the factory bean for repositories.
   *
   * @return the repository factory bean class
   */
  @AliasFor(annotation = EnableJpaRepositories.class, attribute = "repositoryFactoryBeanClass")
  Class<?> repositoryFactoryBeanClass() default JpaRepositoryFactoryBean.class;

  /**
   * Alias for the 'repositoryBaseClass' attribute in {@link EnableJpaRepositories}.
   * Specifies the base class for repositories.
   *
   * @return the repository base class
   */
  @AliasFor(annotation = EnableJpaRepositories.class, attribute = "repositoryBaseClass")
  Class<?> repositoryBaseClass() default BaseRepositoryImpl.class;

  /**
   * Alias for the 'nameGenerator' attribute in {@link EnableJpaRepositories}.
   * Specifies the class for generating bean names.
   *
   * @return the name generator class
   */
  @AliasFor(annotation = EnableJpaRepositories.class, attribute = "nameGenerator")
  Class<? extends BeanNameGenerator> nameGenerator() default BeanNameGenerator.class;

  /**
   * Alias for the 'entityManagerFactoryRef' attribute in {@link EnableJpaRepositories}.
   * Specifies the bean name of the EntityManagerFactory to use.
   *
   * @return the entity manager factory reference
   */
  @AliasFor(annotation = EnableJpaRepositories.class, attribute = "entityManagerFactoryRef")
  String entityManagerFactoryRef() default "entityManagerFactory";

  /**
   * Alias for the 'transactionManagerRef' attribute in {@link EnableJpaRepositories}.
   * Specifies the bean name of the transaction manager to use.
   *
   * @return the transaction manager reference
   */
  @AliasFor(annotation = EnableJpaRepositories.class, attribute = "transactionManagerRef")
  String transactionManagerRef() default "transactionManager";

  /**
   * Alias for the 'considerNestedRepositories' attribute in {@link EnableJpaRepositories}.
   * Determines whether nested repository interfaces should be considered.
   *
   * @return true if nested repositories are considered; false otherwise
   */
  @AliasFor(annotation = EnableJpaRepositories.class, attribute = "considerNestedRepositories")
  boolean considerNestedRepositories() default false;

  /**
   * Alias for the 'enableDefaultTransactions' attribute in {@link EnableJpaRepositories}.
   * Indicates whether default transactions are enabled.
   *
   * @return true if default transactions are enabled; false otherwise
   */
  @AliasFor(annotation = EnableJpaRepositories.class, attribute = "enableDefaultTransactions")
  boolean enableDefaultTransactions() default true;

  /**
   * Alias for the 'bootstrapMode' attribute in {@link EnableJpaRepositories}.
   * Specifies the bootstrap mode for JPA repositories.
   *
   * @return the bootstrap mode
   */
  @AliasFor(annotation = EnableJpaRepositories.class, attribute = "bootstrapMode")
  BootstrapMode bootstrapMode() default BootstrapMode.DEFAULT;

  /**
   * Alias for the 'escapeCharacter' attribute in {@link EnableJpaRepositories}.
   * Specifies the escape character for queries.
   *
   * @return the escape character
   */
  @AliasFor(annotation = EnableJpaRepositories.class, attribute = "escapeCharacter")
  char escapeCharacter() default '\\';

  /**
   * Alias for the 'pageSerializationMode' attribute in {@link EnableSpringDataWebSupport}.
   * Specifies the mode of page serialization for web support.
   *
   * @return the page serialization mode
   */
  @AliasFor(annotation = EnableSpringDataWebSupport.class, attribute = "pageSerializationMode")
  EnableSpringDataWebSupport.PageSerializationMode pageSerializationMode() default
      EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;
}

