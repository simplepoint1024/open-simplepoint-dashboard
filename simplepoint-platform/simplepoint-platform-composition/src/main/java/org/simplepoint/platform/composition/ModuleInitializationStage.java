package org.simplepoint.platform.composition;

/** Framework-owned startup stages, independent of the order modules were discovered. */
public enum ModuleInitializationStage {
  /** Existing base-data contributions run before declaration registration. */
  BOOTSTRAP,
  /** Registers selected module declarations against prepared domain services. */
  REGISTRATION
}
