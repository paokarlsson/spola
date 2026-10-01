// Fixture: "café" ends in a Latin-1 byte, which is not UTF-8. The launcher replaces it and
// runs the file; the check analyses the UTF-8 reading and reports the Latin-1 reading,
// which is a different text that javac also parses.
class Latin1 {
    @interface Tag {
    }

    @Tag
    public static void main(String[] args) {
        System.out.println("ran");
    }
}
