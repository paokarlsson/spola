package fx;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Fixture: annotation types that end up in bytecode (RUNTIME is visible, CLASS is invisible).
final class Marker {

    @Retention(RetentionPolicy.RUNTIME)
    @interface Visible {
    }

    @Retention(RetentionPolicy.CLASS)
    @interface Invisible {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface Param {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    @interface TypeUse {
    }

    private Marker() {
    }
}
