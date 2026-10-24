package org.simplepoint.persistencefixture.beta;

import org.springframework.data.jpa.repository.JpaRepository;

/** Exercises the standard Spring Data repository registrar. */
public interface BetaRepository extends JpaRepository<BetaRecord, String> {}
