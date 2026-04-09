/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.data.amqp.rpc;

/**
 * Serializable remote invocation error payload returned over AMQP.
 */
public final class RemoteInvocationError {

  private String type;

  private String message;

  /**
   * Creates an empty error payload for deserialization.
   */
  public RemoteInvocationError() {
  }

  /**
   * Creates a remote error payload with the supplied type and message.
   *
   * @param type the remote exception type
   * @param message the remote exception message
   */
  public RemoteInvocationError(final String type, final String message) {
    this.type = type;
    this.message = message;
  }

  public String getType() {
    return type;
  }

  public void setType(final String type) {
    this.type = type;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(final String message) {
    this.message = message;
  }
}
