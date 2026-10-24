package org.simplepoint.composition.fixture.api;

import java.util.ArrayList;
import java.util.List;

/** Observable local business work, separate from the remote registry fixture. */
public final class BootstrapEffects {
  private final List<String> entries = new ArrayList<>();

  /** Records one business action. */
  public void add(String entry) {
    entries.add(entry);
  }

  /** Returns completed business work. */
  public List<String> entries() {
    return List.copyOf(entries);
  }
}
