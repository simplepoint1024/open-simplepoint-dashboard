package org.simplepoint.plugin.workflow.api.model;

import org.simplepoint.core.query.QueryField;
import org.simplepoint.core.query.TableQuery;

/**
 * Public, published catalog metadata for starters; never exposes draft XML or editor permissions.
 */
@TableQuery(
    maxPageSize = 100,
    value = {
      @QueryField("name"),
      @QueryField("category"),
      @QueryField("description"),
      @QueryField("latestVersion")
    })
public record WorkflowStartOption(
    String id, String name, String category, String description, int latestVersion) {}
