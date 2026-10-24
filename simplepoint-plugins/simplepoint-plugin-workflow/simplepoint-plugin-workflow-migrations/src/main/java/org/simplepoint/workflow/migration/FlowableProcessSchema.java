package org.simplepoint.workflow.migration;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.simplepoint.platform.composition.ModuleSharedSchemas;

/** Requires the platform's complete, versioned Flowable process schema in the owner's store. */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ModuleSharedSchemas(FlowableProcessMigrations.OWNER)
public @interface FlowableProcessSchema {}
