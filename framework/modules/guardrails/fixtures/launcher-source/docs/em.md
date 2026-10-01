// Fixture: "x—" (an em dash) is no identifier in UTF-8, so this is not Java as UTF-8.
// Read as ISO-8859-1 ("java -Dfile.encoding=ISO-8859-1 --source 25 em.md") it is: the dash
// becomes "â" and two ignorable characters. That reading is reported.
class Em {
    @interface Hidden {
    }

    @Hidden
    public static void main(String[] args) {
        int x— = 1;
        System.out.println("ran " + x—);
    }
}
