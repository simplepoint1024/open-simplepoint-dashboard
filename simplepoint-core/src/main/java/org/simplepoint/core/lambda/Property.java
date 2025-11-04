package org.simplepoint.core.lambda;

/**
 * A functional interface representing a property accessor.
 *
 * @param <T> the type of the input to the function
 * @param <R> the type of the result of the function
 */
public interface Property<T, R> extends java.io.Serializable, java.util.function.Function<T, R> {
}
