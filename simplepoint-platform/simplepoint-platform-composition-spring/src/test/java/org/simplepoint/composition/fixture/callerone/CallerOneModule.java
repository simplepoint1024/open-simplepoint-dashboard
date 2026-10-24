package org.simplepoint.composition.fixture.callerone;

import java.util.List;
import org.simplepoint.composition.fixture.api.CallerChain;
import org.simplepoint.composition.fixture.api.CallerIdentity;
import org.simplepoint.platform.composition.PlatformModule;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** Consumer fixture with every supported injection form. */
@Configuration(proxyBeanMethods = false)
@PlatformModule(
    id = "caller-one",
    packages = "org.simplepoint.composition.fixture.callerone",
    consumes = {CallerIdentity.class, CallerChain.class})
public class CallerOneModule {

  /** A final business component needs no class proxy for caller binding. */
  @Component
  public static final class Consumer {
    private final ObjectProvider<CallerIdentity> deferred;
    private final CallerIdentity lazy;
    private final CallerChain chain;

    @Autowired private CallerIdentity field;

    /** Captures lazy, deferred and ordinary contract dependencies. */
    public Consumer(
        ObjectProvider<CallerIdentity> deferred, @Lazy CallerIdentity lazy, CallerChain chain) {
      this.deferred = deferred;
      this.lazy = lazy;
      this.chain = chain;
    }

    /** Invokes a field-injected client. */
    public String field() {
      return field.caller();
    }

    /** Resolves a client after bean construction. */
    public String deferred() {
      return deferred.getObject().caller();
    }

    /** Invokes a lazily resolved client. */
    public String lazy() {
      return lazy.caller();
    }

    /** Starts a call which enters another module and calls a third. */
    public List<String> chain() {
      return chain.callers();
    }
  }
}
