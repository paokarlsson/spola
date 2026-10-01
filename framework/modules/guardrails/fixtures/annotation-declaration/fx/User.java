package fx;

import java.util.List;

// Fixture: uses of the custom annotations; "@Override" resolves to fx.Override, not java.lang.Override.
class User {

    @Override
    public String toString() {
        return "user";
    }

    List<@Tag String> names() {
        return List.of();
    }
}
