package org.simplepoint.plugin.personal.service;

import java.time.Duration;
import java.time.Instant;
import org.simplepoint.core.query.TablePagination;
import org.simplepoint.plugin.personal.repository.PersonalRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Bounded expiration of the personal message recycle bin. */
@Component
public class PersonalMessageRetention {
  private static final Duration RETENTION = Duration.ofDays(30);
  private final PersonalRepository repository;

  public PersonalMessageRetention(PersonalRepository repository) {
    this.repository = repository;
  }

  @Scheduled(fixedDelayString = "${simplepoint.personal.message-trash.cleanup-delay:5m}")
  @Transactional
  public void expire() {
    repository.purgeExpiredMessages(Instant.now().minus(RETENTION), TablePagination.MAX_PAGE_SIZE);
  }
}
