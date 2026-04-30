package org.simplepoint.security;

import lombok.Data;

/**
 * Lightweight feature definition used by menu initialization configuration.
 */
@Data
public class MenuFeatureDefinition {
    private String name;
    private String description;
    private String code;
    private Boolean publicAccess;
}
