/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.oidc.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import lombok.Data;


/**
 * Entity representing OAuth2 authorization consent.
 *
 * <p>This class stores information about a user's consent to an OAuth2 client,
 * including the granted authorities.
 * </p>
 */
@Data
@Entity
@Table(name = "security_oauth2_consent")
@IdClass(AuthorizationConsent.AuthorizationConsentId.class)
public class AuthorizationConsent {

  /**
   * The registered client ID associated with this authorization consent.
   */
  @Id
  private String registeredClientId;

  /**
   * The principal (user) name associated with this authorization consent.
   */
  @Id
  private String principalName;

  /**
   * The authorities granted by the user to the client.
   */
  @Column(length = 1000)
  private String authorities;

  /**
   * Composite key for {@link AuthorizationConsent}, representing the registered client ID
   * and principal name.
   */
  @Data
  public static class AuthorizationConsentId implements Serializable {

    /**
     * The registered client ID, part of the composite primary key.
     */
    private String registeredClientId;

    /**
     * The principal (user) name, part of the composite primary key.
     */
    private String principalName;

    /**
     * Determines whether two AuthorizationConsentId objects are equal.
     *
     * @param o the object to compare
     * @return {@code true} if the objects are equal, {@code false} otherwise
     */
    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      AuthorizationConsentId that = (AuthorizationConsentId) o;
      return registeredClientId.equals(that.registeredClientId)
          && principalName.equals(that.principalName);
    }

    /**
     * Generates a hash code for the AuthorizationConsentId.
     *
     * @return the computed hash code
     */
    @Override
    public int hashCode() {
      return Objects.hash(registeredClientId, principalName);
    }
  }
}