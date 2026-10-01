// Fixture (gauntlet round 5): "java --source 8 release.md" binds getFirst() to
// Main.getFirst (ArrayList has no getFirst at release 8) and so reads @Deprecated
// reflectively; at --source 25 it binds to List.getFirst and does not. A document is
// therefore never analysed: it parses as Java, so it is reported.
@Deprecated
class Main {
    static Class<?> getFirst() { return Main.class; }
    static final class U { boolean isAnnotationPresent(Class<?> c) { return false; } }
    static class Inner extends java.util.ArrayList<U> {
        boolean test() { return getFirst().isAnnotationPresent(Deprecated.class); }
    }
    public static void main(String[] a) {
        Inner i = new Inner();
        i.add(new U());
        System.out.println("reflective read of @Deprecated: " + i.test());
    }
}
