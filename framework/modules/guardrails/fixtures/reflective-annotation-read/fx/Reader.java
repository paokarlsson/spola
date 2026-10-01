package fx;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

// Fixture: logic driven by reading annotations via reflection.
class Reader {

    boolean deprecated(Class<?> type) {
        return type.isAnnotationPresent(Deprecated.class);
    }

    Object first(Method method) {
        return method.getAnnotation(Deprecated.class);
    }

    long count(Method[] methods) {
        Function<Method, Object[]> all = Method::getDeclaredAnnotations;
        return Arrays.stream(methods).map(all).count();
    }

    // Negative control: getName is not an annotation read.
    String name(Class<?> type) {
        return type.getName();
    }

    int parameters(Method method) {
        return method.getParameterAnnotations()[0].length;
    }

    Object typeUse(Method method, Field field, Class<?> type) {
        return List.of(method.getAnnotatedReturnType(), field.getAnnotatedType(), type.getAnnotatedSuperclass());
    }

    Object defaults(Method method) {
        return method.getDefaultValue();
    }

    Object references(Parameter parameter) {
        Function<Parameter, Annotation[]> all = Parameter::getDeclaredAnnotations;
        Function<Parameter, AnnotatedType> type = Parameter::getAnnotatedType;
        return List.of(all.apply(parameter), type.apply(parameter));
    }

    // Negative controls: no annotations come back (isAnnotation asks what kind of class it is).
    Object plain(Method method, Class<?> type) {
        return List.of(method.getParameterTypes(), method.getReturnType(), type.isAnnotation());
    }
}
