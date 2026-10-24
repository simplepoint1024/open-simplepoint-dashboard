package org.simplepoint.persistencefixture.alpha;

import org.springframework.data.jpa.repository.JpaRepository;

/** Exercises the standard Spring Data repository registrar. */
public interface AlphaRepository extends JpaRepository<AlphaRecord, String> {}
