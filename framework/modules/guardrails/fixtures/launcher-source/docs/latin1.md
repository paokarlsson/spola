// Fixture: "café" ends in a Latin-1 byte, which is not UTF-8. The launcher replaces it and
// runs the file. The check reads it the same way (malformed input replaced), finds that
// javac parses it, and reports it.
class Latin1 {
    @interface Tag {
    }

    @Tag
    public static void main(String[] args) {
        System.out.println("ran");
    }
}
