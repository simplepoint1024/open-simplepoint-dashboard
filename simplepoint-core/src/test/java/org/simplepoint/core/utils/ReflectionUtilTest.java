package org.simplepoint.core.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReflectionUtilTest {

  static class Parent {
    private String parentField = "parent";

    public String getParentField() {
      return parentField;
    }
  }

  static class Child extends Parent {
    private String childField = "child";

    public String getChildField() {
      return childField;
    }
  }

  static class GrandChild extends Child {
    private String grandChildField = "grandChild";
  }

  // -------- getField --------

  @Test
  void getField_existsOnClass_returnsField() throws NoSuchFieldException {
    Field f = ReflectionUtil.getField(Child.class, "childField");
    assertThat(f.getName()).isEqualTo("childField");
  }

  @Test
  void getField_existsOnSuperClass_returnsField() throws NoSuchFieldException {
    Field f = ReflectionUtil.getField(Child.class, "parentField");
    assertThat(f.getName()).isEqualTo("parentField");
  }

  @Test
  void getField_existsOnGrandParent_returnsField() throws NoSuchFieldException {
    Field f = ReflectionUtil.getField(GrandChild.class, "parentField");
    assertThat(f.getName()).isEqualTo("parentField");
  }

  @Test
  void getField_notFound_throwsNoSuchFieldException() {
    assertThatThrownBy(() -> ReflectionUtil.getField(Child.class, "nonexistent"))
        .isInstanceOf(NoSuchFieldException.class);
  }

  // -------- getMethod --------

  @Test
  void getMethod_existsOnClass_returnsMethod() throws NoSuchMethodException {
    Method m = ReflectionUtil.getMethod(Child.class, "getChildField");
    assertThat(m.getName()).isEqualTo("getChildField");
  }

  @Test
  void getMethod_notFound_throwsNoSuchMethodException() {
    assertThatThrownBy(() -> ReflectionUtil.getMethod(Child.class, "nonexistentMethod"))
        .isInstanceOf(NoSuchMethodException.class);
  }

  // -------- getFields --------

  @Test
  void getFields_returnsOnlyDeclaredFields() {
    List<Field> fields = ReflectionUtil.getFields(Child.class);
    assertThat(fields).extracting(Field::getName).containsExactly("childField");
  }

  // -------- getFieldsSuperClasses --------

  @Test
  void getFieldsSuperClasses_includesFieldsFromAllLevels() {
    List<Field> fields = ReflectionUtil.getFieldsSuperClasses(GrandChild.class);
    assertThat(fields).extracting(Field::getName)
        .contains("grandChildField", "childField", "parentField");
  }

  // -------- getSuperClasses --------

  @Test
  void getSuperClasses_returnsAllIntermediateClasses() {
    List<Class<?>> superClasses = ReflectionUtil.getSuperClasses(GrandChild.class);
    assertThat(superClasses).containsExactly(Child.class, Parent.class);
  }

  @Test
  void getSuperClasses_noParentBeyondObject_returnsEmpty() {
    List<Class<?>> superClasses = ReflectionUtil.getSuperClasses(Parent.class);
    assertThat(superClasses).isEmpty();
  }

  // -------- getFieldValue --------

  @Test
  void getFieldValue_directField_returnsValue() throws NoSuchFieldException, IllegalAccessException {
    Child child = new Child();
    Object value = ReflectionUtil.getFieldValue(child, "childField");
    assertThat(value).isEqualTo("child");
  }

  @Test
  void getFieldValue_inheritedField_returnsValue() throws NoSuchFieldException, IllegalAccessException {
    Child child = new Child();
    Object value = ReflectionUtil.getFieldValue(child, "parentField");
    assertThat(value).isEqualTo("parent");
  }

  @Test
  void getFieldValue_missingField_throwsNoSuchFieldException() {
    Child child = new Child();
    assertThatThrownBy(() -> ReflectionUtil.getFieldValue(child, "missing"))
        .isInstanceOf(NoSuchFieldException.class);
  }

  // -------- getFieldValueMethod --------

  @Test
  void getFieldValueMethod_returnsValueViaGetter()
      throws Exception {
    Child child = new Child();
    Object value = ReflectionUtil.getFieldValueMethod(child, "childField");
    assertThat(value).isEqualTo("child");
  }

  @Test
  void getFieldValueMethod_missingGetter_throwsNoSuchMethodException() {
    GrandChild gc = new GrandChild();
    // GrandChild has no getter for grandChildField
    assertThatThrownBy(() -> ReflectionUtil.getFieldValueMethod(gc, "grandChildField"))
        .isInstanceOf(NoSuchMethodException.class);
  }
}
