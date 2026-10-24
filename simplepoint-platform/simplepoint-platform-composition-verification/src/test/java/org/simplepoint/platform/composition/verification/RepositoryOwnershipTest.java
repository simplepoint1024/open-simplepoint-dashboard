package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleDescriptor;
import org.simplepoint.platform.composition.ModulePersistence;
import org.springframework.asm.AnnotationVisitor;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.FieldVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.Type;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Prevents direct repository references from masquerading as remote-capable dependencies. */
class RepositoryOwnershipTest {

  @Test
  void referencesToAnotherDomainRepositoryRequireItsLocalDependencyClosure() throws Exception {
    var catalog = ModuleCatalog.discover(getClass().getClassLoader());
    List<ModuleDescriptor> modules = List.copyOf(catalog.modules());
    Map<String, Set<String>> closures =
        modules.stream()
            .collect(
                Collectors.toMap(
                    ModuleDescriptor::id,
                    module ->
                        catalog.plan(List.of(module.id())).modules().stream()
                            .map(ModuleDescriptor::id)
                            .collect(Collectors.toSet())));
    var violations = new TreeSet<String>();
    var storageEdges = new TreeSet<String>();
    var unboundInjections = new TreeSet<String>();
    var resources =
        new PathMatchingResourcePatternResolver(getClass().getClassLoader())
            .getResources("classpath*:org/simplepoint/**/*.class");
    for (var resource : resources) {
      ClassReader reader;
      try (var input = resource.getInputStream()) {
        reader = new ClassReader(input);
      }
      String source = reader.getClassName().replace('/', '.');
      String owner = owner(modules, source);
      if (owner == null) {
        continue;
      }
      var references = new RepositoryReferences();
      reader.accept(references, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
      references.unboundInjections.forEach(member -> unboundInjections.add(source + ":" + member));
      for (String repository : references.repositories) {
        String target = owner(modules, repository);
        if (target != null && !owner.equals(target)) {
          var sourceModule =
              modules.stream()
                  .filter(module -> module.id().equals(owner))
                  .findFirst()
                  .orElseThrow();
          var storage = sourceModule.configuration().getDeclaredAnnotation(ModulePersistence.class);
          if (storage == null || !List.of(storage.sameStoreAs()).contains(target)) {
            storageEdges.add(owner + " -> " + target);
          }
        }
        if (target != null && !closures.get(owner).contains(target)) {
          violations.add(owner + " -> " + target + ": " + source + " uses " + repository);
        }
      }
    }
    assertEquals(
        Set.of(), unboundInjections, "Persistence resources must use module-bound injection");
    assertEquals(Set.of(), violations, "Missing local dependencies: " + violations);
    assertEquals(
        Set.of(),
        storageEdges,
        "Direct repository dependencies need shared storage: " + storageEdges);
  }

  private static String owner(final List<ModuleDescriptor> modules, final String type) {
    return modules.stream()
        .filter(module -> module.packages().stream().anyMatch(root -> type.startsWith(root + ".")))
        .map(ModuleDescriptor::id)
        .findFirst()
        .orElse(null);
  }

  private static final class RepositoryReferences extends ClassVisitor {
    private final Set<String> repositories = new HashSet<>();
    private final Set<String> unboundInjections = new HashSet<>();

    private RepositoryReferences() {
      super(Opcodes.ASM9);
    }

    @Override
    public FieldVisitor visitField(
        final int access,
        final String name,
        final String descriptor,
        final String signature,
        final Object value) {
      add(Type.getType(descriptor));
      return new FieldVisitor(Opcodes.ASM9) {
        @Override
        public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
          checkInjection(name, annotation);
          return null;
        }
      };
    }

    @Override
    public MethodVisitor visitMethod(
        final int access,
        final String name,
        final String descriptor,
        final String signature,
        final String[] exceptions) {
      for (var parameter : Type.getArgumentTypes(descriptor)) {
        add(parameter);
      }
      add(Type.getReturnType(descriptor));
      return new MethodVisitor(Opcodes.ASM9) {
        @Override
        public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
          checkInjection(name, annotation);
          return null;
        }

        @Override
        public void visitMethodInsn(
            final int opcode,
            final String owner,
            final String method,
            final String desc,
            final boolean isInterface) {
          add(Type.getObjectType(owner));
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
          add(Type.getObjectType(type));
        }
      };
    }

    private void checkInjection(String member, String annotation) {
      if (annotation.equals("Ljakarta/persistence/PersistenceContext;")
          || annotation.equals("Ljakarta/persistence/PersistenceUnit;")) {
        unboundInjections.add(member);
      }
    }

    private void add(final Type type) {
      if (type.getSort() == Type.ARRAY) {
        add(type.getElementType());
      } else if (type.getSort() == Type.OBJECT) {
        String name = type.getClassName().split("\\$", 2)[0];
        if (name.contains(".repository.") && name.endsWith("Repository")) {
          repositories.add(name);
        }
      }
    }
  }
}
