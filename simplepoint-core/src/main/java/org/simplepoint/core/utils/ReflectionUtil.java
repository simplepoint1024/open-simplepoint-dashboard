/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.core.utils;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A utility class for reflection-based operations on Java objects and classes.
 */
public class ReflectionUtil {

  /**
   * Retrieves a {@link Field} object for the specified field name in the given class.
   * Searches superclasses if the field is not found in the current class.
   *
   * @param clazz     the class where the field is searched
   * @param fieldName the name of the field
   * @return the {@link Field} object for the given field name
   * @throws NoSuchFieldException if no such field is found
   */
  public static Field getField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
    try {
      return clazz.getDeclaredField(fieldName);
    } catch (NoSuchFieldException exception) {
      Class<?> superclass = clazz.getSuperclass();
      if (superclass != null && superclass != Object.class) {
        return getField(superclass, fieldName);
      }
      throw exception;
    }
  }

  /**
   * Retrieves a {@link Method} object for the specified method name in the given class.
   *
   * @param clazz      the class where the method is searched
   * @param methodName the name of the method
   * @return the {@link Method} object for the given method name
   * @throws NoSuchMethodException if no such method is found
   */
  public static Method getMethod(Class<?> clazz, String methodName) throws NoSuchMethodException {
    return clazz.getDeclaredMethod(methodName);
  }

  /**
   * Retrieves all declared fields of the given class.
   *
   * @param clazz the class whose fields are retrieved
   * @return a list of {@link Field} objects representing the declared fields
   */
  public static List<Field> getFields(Class<?> clazz) {
    Field[] declaredFields = clazz.getDeclaredFields();
    return Arrays.asList(declaredFields);
  }

  /**
   * Retrieves all fields from the given class and its superclasses.
   *
   * @param clazz the class whose fields are retrieved
   * @return a list of {@link Field} objects from the class and superclasses
   */
  public static List<Field> getFieldsSuperClasses(Class<?> clazz) {
    List<Field> fields = new ArrayList<>(getFields(clazz));
    getSuperClasses(clazz).forEach(classes -> fields.addAll(getFields(classes)));
    return fields;
  }

  /**
   * Retrieves all superclasses of the given class.
   *
   * @param clazz the class whose superclasses are retrieved
   * @return a list of {@link Class} objects representing the superclasses
   */
  public static List<Class<?>> getSuperClasses(Class<?> clazz) {
    List<Class<?>> list = new ArrayList<>();
    Class<?> superclass = clazz.getSuperclass();
    while (superclass != Object.class) {
      list.add(superclass);
      superclass = superclass.getSuperclass();
    }
    return list;
  }

  /**
   * Retrieves the value of the specified field from the given object.
   *
   * @param object    the object whose field value is retrieved
   * @param fieldName the name of the field
   * @return the value of the field
   * @throws NoSuchFieldException   if the field is not found
   * @throws IllegalAccessException if access to the field is denied
   */
  public static Object getFieldValue(Object object, String fieldName)
      throws NoSuchFieldException, IllegalAccessException {
    Field field = getField(object.getClass(), fieldName);
    field.setAccessible(true);
    return field.get(object);
  }

  /**
   * Retrieves the value of a field using its getter method from the given object.
   *
   * @param object    the object whose field value is retrieved
   * @param fieldName the name of the field
   * @return the value of the field
   * @throws NoSuchMethodException     if the getter method is not found
   * @throws InvocationTargetException if the method invocation fails
   * @throws IllegalAccessException    if access to the method is denied
   */
  public static Object getFieldValueMethod(Object object, String fieldName)
      throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
    Method method = getMethod(object.getClass(),
        "get" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1));
    method.setAccessible(true);
    return method.invoke(object);
  }
}
