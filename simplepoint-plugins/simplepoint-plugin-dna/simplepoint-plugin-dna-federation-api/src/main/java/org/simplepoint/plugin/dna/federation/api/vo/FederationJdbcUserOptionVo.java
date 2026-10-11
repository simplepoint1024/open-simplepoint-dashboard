package org.simplepoint.plugin.dna.federation.api.vo;

/** Minimal enabled-user projection for assigning JDBC connection access. */
public record FederationJdbcUserOptionVo(String id, String name, String email,
                                        String phoneNumber) {}
