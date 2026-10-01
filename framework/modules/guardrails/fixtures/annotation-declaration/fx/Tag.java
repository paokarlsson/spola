package fx;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Fixture: a custom annotation type; its meta-annotations are not allowed either.
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE_USE)
@interface Tag {
}
