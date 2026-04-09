/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.data.amqp.rpc;

import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpMessageReturnedException;

/**
 * Exception thrown when a remote AMQP RPC invocation fails.
 */
public final class RemoteInvocationException extends RuntimeException {

  private final String target;

  private final String remoteType;

  private RemoteInvocationException(final String message, final String target,
                                    final String remoteType, final Throwable cause) {
    super(message, cause);
    this.target = target;
    this.remoteType = remoteType;
  }

  /**
   * Creates an exception describing a timeout waiting for a remote reply.
   *
   * @param target the resolved routing target
   * @param signature the remote method signature
   * @return a timeout exception
   */
  public static RemoteInvocationException timeout(final String target, final String signature) {
    return new RemoteInvocationException(
        "AMQP RPC timed out waiting for response from [" + target + "] for " + signature,
        target,
        null,
        null
    );
  }

  /**
   * Creates an exception from an explicit remote error payload.
   *
   * @param target the resolved routing target
   * @param signature the remote method signature
   * @param error the remote error payload
   * @return a remote invocation exception
   */
  public static RemoteInvocationException remote(final String target, final String signature,
                                                 final RemoteInvocationError error) {
    String errorType = error == null || error.getType() == null || error.getType().isBlank()
        ? RuntimeException.class.getName()
        : error.getType();
    String errorMessage = error == null || error.getMessage() == null || error.getMessage().isBlank()
        ? "Remote invocation failed"
        : error.getMessage();
    return new RemoteInvocationException(
        "AMQP RPC failed on [" + target + "] for " + signature + ": "
            + errorType + ": " + errorMessage,
        target,
        errorType,
        null
    );
  }

  /**
   * Creates an exception describing an unsupported RPC protocol version.
   *
   * @param target the resolved routing target
   * @param signature the remote method signature
   * @param protocolVersion the received protocol version
   * @return a protocol compatibility exception
   */
  public static RemoteInvocationException protocol(final String target, final String signature,
                                                   final String protocolVersion) {
    return new RemoteInvocationException(
        "AMQP RPC received unsupported protocol version ["
            + protocolVersion
            + "] from ["
            + target
            + "] for "
            + signature
            + ", supported ["
            + RemoteProtocol.PROTOCOL_VERSION
            + "]",
        target,
        IllegalStateException.class.getName(),
        null
    );
  }

  /**
   * Creates an exception describing an unroutable publish attempt.
   *
   * @param target the resolved routing target
   * @param signature the remote method signature
   * @param exception the returned-message exception from RabbitMQ
   * @return an unroutable publish exception
   */
  public static RemoteInvocationException unroutable(final String target, final String signature,
                                                     final AmqpMessageReturnedException exception) {
    return new RemoteInvocationException(
        "AMQP RPC publish to ["
            + target
            + "] for "
            + signature
            + " was returned by broker: "
            + exception.getReplyText()
            + " (exchange="
            + exception.getExchange()
            + ", routingKey="
            + exception.getRoutingKey()
            + ")",
        target,
        AmqpMessageReturnedException.class.getName(),
        exception
    );
  }

  /**
   * Creates an exception describing a broker transport failure before a reply is received.
   *
   * @param target the resolved routing target
   * @param signature the remote method signature
   * @param exception the broker transport exception
   * @return a broker transport exception
   */
  public static RemoteInvocationException transport(final String target, final String signature,
                                                    final AmqpException exception) {
    return new RemoteInvocationException(
        "AMQP RPC publish to ["
            + target
            + "] for "
            + signature
            + " failed before a reply was received: "
            + exception.getMessage(),
        target,
        exception.getClass().getName(),
        exception
    );
  }

  /**
   * Creates an exception describing a publisher confirm nack.
   *
   * @param target the resolved routing target
   * @param signature the remote method signature
   * @param reason the broker supplied nack reason
   * @return a publisher confirm nack exception
   */
  public static RemoteInvocationException publisherConfirmNack(final String target, final String signature,
                                                              final String reason) {
    String nackReason = reason == null || reason.isBlank() ? "nack without reason" : reason;
    return new RemoteInvocationException(
        "AMQP RPC publish to ["
            + target
            + "] for "
            + signature
            + " was nacked by broker: "
            + nackReason,
        target,
        IllegalStateException.class.getName(),
        null
    );
  }

  /**
   * Creates an exception describing a publisher confirm timeout.
   *
   * @param target the resolved routing target
   * @param signature the remote method signature
   * @param timeoutMillis the configured confirm timeout
   * @return a publisher confirm timeout exception
   */
  public static RemoteInvocationException publisherConfirmTimeout(final String target, final String signature,
                                                                 final long timeoutMillis) {
    return new RemoteInvocationException(
        "AMQP RPC publish to ["
            + target
            + "] for "
            + signature
            + " did not receive a broker confirm within "
            + timeoutMillis
            + " ms",
        target,
        TimeoutException.class.getName(),
        null
    );
  }

  /**
   * Creates an exception describing an unexpected publisher confirm failure.
   *
   * @param target the resolved routing target
   * @param signature the remote method signature
   * @param cause the underlying publisher confirm failure
   * @return a publisher confirm failure exception
   */
  public static RemoteInvocationException publisherConfirmFailure(final String target, final String signature,
                                                                 final Throwable cause) {
    Throwable actual = cause == null ? new IllegalStateException("Unknown publisher confirm failure") : cause;
    return new RemoteInvocationException(
        "AMQP RPC publish to ["
            + target
            + "] for "
            + signature
            + " failed while waiting for broker confirm: "
            + actual.getMessage(),
        target,
        actual.getClass().getName(),
        actual
    );
  }

  public String getTarget() {
    return target;
  }

  public String getRemoteType() {
    return remoteType;
  }
}
