package org.simplepoint.platform.composition.verification;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.platform.composition.ModuleCatalog;

/** Prevents persistence/framework APIs from returning to migrated public capabilities. */
class RemoteContractBoundaryTest {

  @Test
  void publicCapabilitiesContainNoEntitiesOrInternalCrud() throws Exception {
    Class<?> baseService = Class.forName("org.simplepoint.api.base.BaseService");
    Class<?> baseEntity = Class.forName("org.simplepoint.api.base.BaseEntity");
    var catalog = ModuleCatalog.discover(getClass().getClassLoader());
    for (var module : catalog.modules()) {
      for (Class<?> contract : module.provides().values()) {
        assertFalse(
            baseService.isAssignableFrom(contract),
            "Internal CRUD exported by " + contract.getName());
        for (var method : contract.getMethods()) {
          var seen = new HashSet<Type>();
          verifyType(method.getGenericReturnType(), baseEntity, seen, method.toString());
          for (Type parameter : method.getGenericParameterTypes()) {
            verifyType(parameter, baseEntity, seen, method.toString());
          }
        }
      }
    }
  }

  private void verifyType(Type type, Class<?> baseEntity, Set<Type> seen, String path) {
    if (type == null || !seen.add(type)) {
      return;
    }
    if (type instanceof ParameterizedType parameterized) {
      verifyType(parameterized.getRawType(), baseEntity, seen, path);
      for (Type argument : parameterized.getActualTypeArguments()) {
        verifyType(argument, baseEntity, seen, path);
      }
    } else if (type instanceof GenericArrayType array) {
      verifyType(array.getGenericComponentType(), baseEntity, seen, path);
    } else if (type instanceof TypeVariable<?> variable) {
      for (Type bound : variable.getBounds()) {
        verifyType(bound, baseEntity, seen, path);
      }
    } else if (type instanceof WildcardType wildcard) {
      for (Type bound : wildcard.getUpperBounds()) {
        verifyType(bound, baseEntity, seen, path);
      }
      for (Type bound : wildcard.getLowerBounds()) {
        verifyType(bound, baseEntity, seen, path);
      }
    } else if (type instanceof Class<?> value) {
      assertFalse(
          baseEntity.isAssignableFrom(value),
          "Entity crosses RPC: " + path + " -> " + value.getName());
      assertFalse(
          value.getName().startsWith("org.springframework.data.")
              || value.getName().startsWith("jakarta.persistence.")
              || value.getName().startsWith("org.springframework.transaction."),
          "Persistence framework crosses RPC: " + path + " -> " + value.getName());
      for (var annotation : value.getAnnotations()) {
        assertFalse(
            annotation.annotationType().getName().equals("jakarta.persistence.Entity"),
            "JPA entity crosses RPC: " + path + " -> " + value.getName());
      }
      if (value.isArray()) {
        verifyType(value.getComponentType(), baseEntity, seen, path);
      } else if (!value.isEnum() && value.getName().startsWith("org.simplepoint.")) {
        verifyType(value.getGenericSuperclass(), baseEntity, seen, path);
        for (var field : value.getDeclaredFields()) {
          if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
            verifyType(field.getGenericType(), baseEntity, seen, path + "." + field.getName());
          }
        }
      }
    }
  }
}
