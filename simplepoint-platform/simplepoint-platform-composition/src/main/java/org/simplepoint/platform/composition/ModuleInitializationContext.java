package org.simplepoint.platform.composition;

/** Restricted module binding supplied by the assembly framework to a declared initializer. */
public interface ModuleInitializationContext {
  /** Returns the logical owner, independent of the deployment host. */
  String moduleId();

  /**
   * Returns the explicitly declared complete resource snapshot owner, never the caller identity.
   */
  default java.util.Optional<String> resourceOwner() {
    return java.util.Optional.empty();
  }

  /**
   * Reserves resource codes during preparation; collisions fail before any registration effects.
   */
  default void claimResourceCodes(java.util.Set<String> codes) {
    throw new ModuleCompositionException("UNBOUND_RESOURCE_DECLARATIONS", moduleId());
  }

  /** Declares logical resource mounts so local providers register before their consumers. */
  default void requireResourceParents(java.util.Map<String, String> codeOwners) {
    throw new ModuleCompositionException("UNBOUND_RESOURCE_DEPENDENCIES", moduleId());
  }

  /** Returns a client bound to this module, restricted to this initializer's declared contracts. */
  <T> T client(Class<T> contract);

  /**
   * Reads a required, unique, bounded asset relative to META-INF/simplepoint/modules/{moduleId}/.
   */
  byte[] resource(String relativePath);
}
