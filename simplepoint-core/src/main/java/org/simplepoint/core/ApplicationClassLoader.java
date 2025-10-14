/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.core;

import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLStreamHandlerFactory;

/**
 * A custom class loader extending {@link URLClassLoader}.
 * Provides various constructors to customize the class
 * loading behavior and supports adding URLs dynamically.
 */
public class ApplicationClassLoader extends URLClassLoader {

  /**
   * Constructs an ApplicationClassLoader with the specified URLs and parent ClassLoader.
   *
   * @param urls   the URLs to be used for loading classes and resources
   * @param parent the parent ClassLoader to delegate class loading
   */
  public ApplicationClassLoader(URL[] urls, ClassLoader parent) {
    super(urls, parent);
  }

  /**
   * Constructs an ApplicationClassLoader with the specified URLs.
   *
   * @param urls the URLs to be used for loading classes and resources
   */
  public ApplicationClassLoader(URL[] urls) {
    super(urls);
  }

  /**
   * Constructs an ApplicationClassLoader with the specified URLs, parent ClassLoader,
   * and a custom {@link URLStreamHandlerFactory}.
   *
   * @param urls    the URLs to be used for loading classes and resources
   * @param parent  the parent ClassLoader to delegate class loading
   * @param factory the {@link URLStreamHandlerFactory} to be used for handling URL streams
   */
  public ApplicationClassLoader(URL[] urls, ClassLoader parent, URLStreamHandlerFactory factory) {
    super(urls, parent, factory);
  }

  /**
   * Constructs an ApplicationClassLoader with the specified name, URLs, and parent ClassLoader.
   *
   * @param name   the name of the class loader
   * @param urls   the URLs to be used for loading classes and resources
   * @param parent the parent ClassLoader to delegate class loading
   */
  public ApplicationClassLoader(String name, URL[] urls, ClassLoader parent) {
    super(name, urls, parent);
  }

  /**
   * Constructs an ApplicationClassLoader with the specified name, URLs, parent ClassLoader,
   * and a custom {@link URLStreamHandlerFactory}.
   *
   * @param name    the name of the class loader
   * @param urls    the URLs to be used for loading classes and resources
   * @param parent  the parent ClassLoader to delegate class loading
   * @param factory the {@link URLStreamHandlerFactory} to be used for handling URL streams
   */
  public ApplicationClassLoader(String name, URL[] urls, ClassLoader parent,
                                URLStreamHandlerFactory factory) {
    super(name, urls, parent, factory);
  }

  /**
   * Adds a new URL to the class loader's search path.
   *
   * @param url the {@link URL} to be added to the search path
   */
  @Override
  public void addURL(URL url) {
    super.addURL(url);
  }
}
