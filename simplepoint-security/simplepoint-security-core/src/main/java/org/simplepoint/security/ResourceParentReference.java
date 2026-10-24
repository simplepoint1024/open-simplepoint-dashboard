package org.simplepoint.security;

/** An explicit logical mount; resource identity and ownership remain with the declaring module. */
public record ResourceParentReference(String owner, String code) {}
