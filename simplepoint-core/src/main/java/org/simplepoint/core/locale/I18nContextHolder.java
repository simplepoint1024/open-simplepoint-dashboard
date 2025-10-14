package org.simplepoint.core.locale;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.simplepoint.core.ApplicationContextProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * I18nContextHolder provides access to the current locale, time zone, and message source.
 * It is used to retrieve internationalization settings and resources in a Spring application.
 */
public class I18nContextHolder {

  public static final String DISABLE_I18N = "disable.i18n";

  /**
   * Private constructor to prevent instantiation.
   * This class is a utility class and should not be instantiated.
   */
  private I18nContextHolder() {
    throw new AssertionError();
  }

  /**
   * Private constructor to prevent instantiation.
   * This class is a utility class and should not be instantiated.
   */
  public static Locale getLocale() {
    return LocaleContextHolder.getLocale();
  }

  /**
   * Returns the current time zone from the LocaleContextHolder.
   * This method is used to retrieve the time zone settings for internationalization purposes.
   *
   * @return the current TimeZone
   */
  public static TimeZone getTimeZone() {
    return LocaleContextHolder.getTimeZone();
  }

  /**
   * Returns the MessageSource bean from the application context.
   * This method is used to access the message source for internationalization.
   *
   * @return the MessageSource bean
   */
  public static MessageSource getMessageSource() {
    return ApplicationContextProvider.getBean(MessageSource.class);
  }

  /**
   * Retrieves a message from the message source using the specified code and default message.
   * This method allows for parameterized messages with optional arguments.
   *
   * @param code           the message code
   * @param defaultMessage the default message to return if the code is not found
   * @param args           optional arguments for parameterized messages
   * @return the resolved message
   */
  public static String getMessage(String code, String defaultMessage, Object... args) {
    return getMessageSource().getMessage(code, args, defaultMessage, getLocale());
  }

  /**
   * Retrieves a message from the message source using the specified code and arguments.
   * This method allows for parameterized messages without a default message.
   *
   * @param code the message code
   * @param args optional arguments for parameterized messages
   * @return the resolved message
   */
  public static String getMessage(String code, Object... args) {
    return getMessageSource().getMessage(code, args, getLocale());
  }

  /**
   * Checks if internationalization (i18n) is disabled based on the provided attributes.
   * If the "enable.i18n" attribute is set to "false", it returns true, indicating that i18n is disabled.
   *
   * @param attributes a map of attributes that may contain the "enable.i18n" key
   * @return true if i18n is disabled, false otherwise
   */
  public static boolean disableI18n(Map<String, String> attributes) {
    return attributes != null && "false".equalsIgnoreCase(attributes.get("enable.i18n"));
  }

  /**
   * Localizes the label of an object using the provided getter and setter functions.
   * If the label is not empty, it retrieves the localized message and sets it on the object.
   *
   * @param obj    the object to localize
   * @param getter a function to get the label from the object
   * @param setter a function to set the localized label on the object
   * @param <T>    the type of the object
   */
  public static <T> void localize(
      T obj,
      Function<T, String> getter,
      BiConsumer<T, String> setter,
      boolean disableI18n
  ) {
    if (obj == null || getter == null || setter == null || disableI18n) {
      return;
    }
    String label = getter.apply(obj);
    if (label != null && !label.isEmpty()) {
      String message = getMessage(label);
      setter.accept(obj, message);
    }
  }

  /**
   * Localizes the labels of a collection of objects using the provided getter and setter functions.
   * It iterates through each object in the collection and applies the localization logic.
   *
   * @param objs   the collection of objects to localize
   * @param getter a function to get the label from each object
   * @param setter a function to set the localized label on each object
   * @param <T>    the type of the objects in the collection
   */
  public static <T> void localize(
      Collection<T> objs,
      Function<T, String> getter,
      BiConsumer<T, String> setter,
      boolean disableI18n
  ) {
    if (objs == null || setter == null || disableI18n) {
      return;
    }
    for (T obj : objs) {
      localize(obj, getter, setter, false);
    }
  }
}
