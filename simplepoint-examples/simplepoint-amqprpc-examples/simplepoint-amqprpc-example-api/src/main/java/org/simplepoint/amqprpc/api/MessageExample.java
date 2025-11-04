package org.simplepoint.amqprpc.api;

import java.io.Serializable;
import lombok.Data;

/**
 * Test message class.
 */
@Data
public class MessageExample implements Serializable {
  private String message;
}
