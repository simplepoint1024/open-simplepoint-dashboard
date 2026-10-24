package org.simplepoint.data.jpa.composition;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Import;

/** Host-level opt-in to module-owned JPA; never combine with a global repository/entity scan. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(ModulePersistenceConfiguration.class)
@ImportAutoConfiguration(
    exclude = {DataJpaRepositoriesAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
public @interface EnableModulePersistence {}
