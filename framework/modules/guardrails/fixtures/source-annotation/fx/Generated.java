package fx;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

// Fixture: disallowed JDK annotations in source code. The allowed ones are negative controls.
@javax.annotation.processing.Generated("fixture")
public class Generated implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String name;

    @java.beans.BeanProperty(description = "fixture")
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }

    @Deprecated
    @SuppressWarnings("unchecked")
    @SafeVarargs
    public static <T> List<T> list(T... items) {
        return List.of(items);
    }

    @FunctionalInterface
    interface Callback {
        void call();
    }
}
