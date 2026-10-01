package spola.guardrails;

/**
 * A ground rule from {@code framework/REGLER.md} that guardrails enforces.
 *
 * <p>Each constant names the section of {@code REGLER.md} it comes from, so that a
 * violation report always says which rule was broken.
 */
public enum Rule {
    /** §2: only the six allowlisted JDK annotations may be used. */
    ANNOTATION_NOT_ALLOWED("2. Annoteringar", "only the allowlisted JDK annotations may be used"),
    /** §2: custom annotation types ({@code @interface}) are forbidden. */
    ANNOTATION_DECLARATION("2. Annoteringar", "custom annotation types (@interface) are forbidden"),
    /** §2: annotations must not be read via reflection. */
    ANNOTATION_REFLECTION("2. Annoteringar", "reading annotations via reflection is forbidden"),
    /** §1: only JDK packages and project packages may be imported. */
    FOREIGN_IMPORT("1. Inga tredjepartsberoenden", "only JDK and project packages may be imported"),
    /** §1: a module may only require JDK modules and project modules. */
    FOREIGN_REQUIRES("1. Inga tredjepartsberoenden", "only JDK modules and project modules may be required"),
    /**
     * §1: jar archives may only exist in the build output directory. An archive is a jar by
     * name ({@code .jar}, {@code .jmod}, {@code .war}, {@code .ear}) or by content (a zip file
     * with compiled Java code, a manifest or a nested archive, whatever its name).
     */
    FOREIGN_JAR("1. Inga tredjepartsberoenden",
            "no jar archives (jar/jmod/war/ear by name, or any zip holding Java code) outside the"
                    + " build output directory (framework/out/)"),
    /** §1: no build files from Maven, Gradle or other third-party build tools. */
    FOREIGN_BUILD_FILE("1. Inga tredjepartsberoenden", "no files from Maven, Gradle or other third-party build tools"),
    /**
     * §1: no symbolic links in the checked tree. A link can pull in code or jars from outside
     * the repository, and the checks do not follow links, so its target would go unchecked.
     */
    SYMBOLIC_LINK("1. Inga tredjepartsberoenden",
            "no symbolic links; they can bring in unchecked code or jars from outside the tree"),
    /**
     * §1 and §2: no Java code the source check cannot analyse. The source launcher and jshell
     * run files of any name, so besides {@code .java} sources only documents ({@code .md}) may
     * exist outside the build output; and a document that javac parses (in some charset, at
     * some release) must be Java source the check can read and attribute. Such code could
     * otherwise hide annotations, {@code @interface} declarations or third-party imports.
     */
    UNCHECKABLE_SOURCE("1. Inga tredjepartsberoenden, 2. Annoteringar",
            "no code the source check cannot analyse: besides .java sources only .md documents may exist"
                    + " outside framework/out/, and a document javac parses must compile as read in UTF-8");

    private final String section;
    private final String description;

    Rule(String section, String description) {
        this.section = section;
        this.description = description;
    }

    /**
     * Returns the section heading of {@code REGLER.md} the rule comes from.
     *
     * @return the section, for example {@code "2. Annoteringar"}
     */
    public String section() {
        return section;
    }

    /**
     * Returns a one-line description of the rule.
     *
     * @return the description
     */
    public String description() {
        return description;
    }
}
