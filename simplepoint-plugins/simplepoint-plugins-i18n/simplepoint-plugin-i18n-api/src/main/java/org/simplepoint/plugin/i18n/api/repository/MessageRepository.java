package org.simplepoint.plugin.i18n.api.repository;

import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.core.entity.Message;

/**
 * MessageRepository provides an interface for managing Message entities.
 * It extends the BaseRepository to inherit basic CRUD operations.
 * This interface is used to interact with the persistence layer for Message entities.
 */
public interface MessageRepository extends BaseRepository<Message, String> {

  /**
   * Retrieves a message by its code and language.
   *
   * @param code     the code of the message
   * @param language the language of the message
   * @return the text of the message, or null if not found
   */
  String getMessage(String code, String language);
}
