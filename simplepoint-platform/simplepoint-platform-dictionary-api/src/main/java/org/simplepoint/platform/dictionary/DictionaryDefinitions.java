package org.simplepoint.platform.dictionary;

/** Trusted metadata source with a public no-argument constructor and no runtime side effects. */
public interface DictionaryDefinitions {
  /** Returns the immutable module-owned contribution; never writes storage or opens connections. */
  DictionaryContribution declaration();
}
