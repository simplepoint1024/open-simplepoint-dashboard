/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

/**
 * URLClassLoader that is aware of dependent plugin classloaders.
 * Load order:
 * - Parent-first for JDK and safe packages (whitelist)
 * - Self (findClass)
 * - Dependencies (in order)
 * - Parent
 */
final class DependencyAwareUrlClassLoader extends URLClassLoader {

  private final List<ClassLoader> dependencies;

  // Packages that should always be resolved by parent first
  private static final String[] PARENT_FIRST_PREFIXES = new String[] {
      "java.", "javax.", "jakarta.", "sun.", "com.sun.",
      "org.slf4j.", "org.apache.logging.", "ch.qos.logback.",
      "org.springframework.", "reactor.", "io.netty."
  };

  DependencyAwareUrlClassLoader(URL[] urls, ClassLoader parent, List<ClassLoader> dependencies) {
    super(urls, parent);
    this.dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
  }

  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    synchronized (getClassLoadingLock(name)) {
      // Parent-first for whitelisted prefixes
      if (isParentFirst(name)) {
        return super.loadClass(name, resolve);
      }

      // Already loaded?
      Class<?> c = findLoadedClass(name);
      if (c != null) {
        return c;
      }

      // Try to find in self first
      try {
        c = findClass(name);
        if (resolve) {
          resolveClass(c);
        }
        return c;
      } catch (ClassNotFoundException ignore) {
        // ignore and continue
      }

      // Try dependencies next
      for (ClassLoader dep : dependencies) {
        try {
          c = dep.loadClass(name);
          if (resolve) {
            resolveClass(c);
          }
          return c;
        } catch (ClassNotFoundException ignored) {
          // continue to next dependency
        }
      }

      // Fallback to parent
      return super.loadClass(name, resolve);
    }
  }

  private boolean isParentFirst(String name) {
    for (String p : PARENT_FIRST_PREFIXES) {
      if (name.startsWith(p)) {
        return true;
      }
    }
    return false;
  }
}

