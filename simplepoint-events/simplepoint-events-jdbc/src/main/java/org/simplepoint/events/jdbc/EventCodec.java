/*
 * Copyright (c) 2026 SimplePoint
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.events.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import org.simplepoint.events.api.EventMessage;

/** Closed JSON envelope; application Jackson polymorphic configuration is deliberately excluded. */
public final class EventCodec {
  public static final int MAX_BYTES = 32768;
  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
      .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  /** Serializes with a bounded wire size. */
  public String encode(EventMessage event) {
    try {
      String json = mapper.writeValueAsString(event);
      checkSize(json.getBytes(StandardCharsets.UTF_8));
      return json;
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("Invalid event JSON", ex);
    }
  }

  /** Reads only the known envelope type, never a class name supplied by the sender. */
  public EventMessage decode(byte[] json) {
    checkSize(json);
    try {
      return mapper.readValue(json, EventMessage.class);
    } catch (Exception ex) {
      throw new IllegalArgumentException("Invalid event envelope", ex);
    }
  }

  private void checkSize(byte[] json) {
    if (json.length == 0 || json.length > MAX_BYTES) {
      throw new IllegalArgumentException("Event exceeds wire size limit");
    }
  }
}
