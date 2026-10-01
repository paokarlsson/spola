package fx;

// Fixture: disallowed annotations on every place the bytecode check must look at.
// @Deprecated is allowed and must not be reported.
@Marker.Visible
@Deprecated
class Marked {

    @Marker.Invisible
    int field;

    java.util.List<@Marker.TypeUse String> names;

    @Marker.Visible
    void method(@Marker.Param String argument) {
        Object value = argument;
        String text = (@Marker.TypeUse String) value;
        field = text.length();
    }

    record Point(@Marker.Invisible int x) {
    }
}
