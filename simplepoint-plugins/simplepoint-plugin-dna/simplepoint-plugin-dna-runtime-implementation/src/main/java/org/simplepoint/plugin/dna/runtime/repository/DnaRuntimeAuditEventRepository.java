package org.simplepoint.plugin.dna.runtime.repository;

import org.simplepoint.plugin.dna.runtime.entity.DnaRuntimeAuditEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Append-only persistence for sanitized Runtime audit events. */
@Repository
public interface DnaRuntimeAuditEventRepository
    extends JpaRepository<DnaRuntimeAuditEventEntity, String> {
}
