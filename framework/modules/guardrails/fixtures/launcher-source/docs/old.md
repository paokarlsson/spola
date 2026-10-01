// Fixture: "_" is an identifier only up to Java 8, so javac parses this only at
// --source 8, and "java --source 8 old.md" runs it. The check tries every release.
class Old {
    @interface Mark {
    }

    @Mark
    public static void main(String[] args) throws Exception {
        _();
        System.out.println(Old.class.getMethod("main", String[].class).isAnnotationPresent(Mark.class));
    }

    static void _() {
    }
}
