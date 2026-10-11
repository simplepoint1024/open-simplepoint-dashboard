package org.simplepoint.plugin.dna.runtime.service.schema;

import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.dialect.DatabaseIdentity;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.springframework.stereotype.Component;

/** Selects exactly one executable schema dialect for a database product. */
@Component
public class RuntimeSchemaDialectResolver {

  private final List<RuntimeSchemaDialect> dialects;

  /** Creates the resolver from installed executable dialects. */
  public RuntimeSchemaDialectResolver(final List<RuntimeSchemaDialect> dialects) {
    this.dialects = List.copyOf(dialects);
  }

  /** Resolves a single dialect or rejects unknown and ambiguous products. */
  public RuntimeSchemaDialect resolve(final DatabaseIdentity database) {
    List<RuntimeSchemaDialect> matches = dialects.stream()
        .filter(dialect -> dialect.supports(database))
        .toList();
    if (matches.size() != 1) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.DIALECT_CAPABILITY_UNKNOWN,
          "Expected one executable schema dialect for " + database.productName()
              + " but found " + matches.size()
      );
    }
    return matches.getFirst();
  }
}
