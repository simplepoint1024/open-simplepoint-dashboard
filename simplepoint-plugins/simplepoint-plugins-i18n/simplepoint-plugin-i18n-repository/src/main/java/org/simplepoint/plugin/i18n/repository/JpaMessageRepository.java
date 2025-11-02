package org.simplepoint.plugin.i18n.repository;

import java.util.Collection;
import org.simplepoint.core.entity.Message;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.i18n.api.repository.MessageRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * JpaMessageRepository provides a JPA implementation of the MessageRepository interface.
 * It extends BaseRepository to inherit basic CRUD operations for Message entities.
 * This repository is used to interact with the persistence layer for Message entities.
 */
@Repository
public interface JpaMessageRepository extends BaseRepository<Message, String>, MessageRepository {

  /**
   * Retrieves a message by its code and locale.
   *
   * @param code   the code of the message
   * @param locale the locale of the message
   * @return the text of the message, or null if not found
   */
  @Override
  @Query("SELECT m.message FROM Message m WHERE m.code = :code AND m.locale = :locale")
  String getMessage(@Param("code") String code, @Param("locale") String locale);

  /**
   * Retrieves global messages based on the provided locale and namespace.
   *
   * @param locale the locale for which messages are requested
   * @param ns     the namespace of the messages
   * @return a collection of Message entities
   */
  @Override
  @Query("SELECT m FROM Message m WHERE m.locale = :locale AND m.namespace in :ns")
  Collection<Message> mapping(@Param("locale") String locale, @Param("ns") String[] ns);

  @Override
  @Query("SELECT m FROM Message m where m.global = true and m.locale = :locale")
  Collection<Message> global(@Param("locale") String locale);
}
