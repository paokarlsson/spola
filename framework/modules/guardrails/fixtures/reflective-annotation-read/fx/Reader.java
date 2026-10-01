package fx;

import java.lang.reflect.Method;
import java.util.Arrays;
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
}
