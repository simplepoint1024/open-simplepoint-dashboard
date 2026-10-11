package org.simplepoint.plugin.ai.core.api.model;

import org.simplepoint.core.annotation.PermissionFields;
import org.simplepoint.core.query.QueryField;
import org.simplepoint.core.query.TableQuery;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;

/** Bounded knowledge-base selector projection; never contains pricing or provider credentials. */
@TableQuery(value = {
    @QueryField(value = "id", operators = {"equals", "in"}),
    @QueryField(value = "name", operators = {"like", "equals"})
}, maxPageSize = 100)
public record AiEmbeddingModelOption(
    @PermissionFields(resource = AiModelDefinition.class, value = "id") String id,
    @PermissionFields(resource = AiModelDefinition.class, value = {"displayName", "modelId"}) String name
) {
}
