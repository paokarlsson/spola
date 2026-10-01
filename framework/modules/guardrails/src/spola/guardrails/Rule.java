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
    /** §1: jar files may only exist in the build output directory. */
    FOREIGN_JAR("1. Inga tredjepartsberoenden", "no jar files outside the build output directory (framework/out/)"),
    /** §1: no build files from Maven, Gradle or other third-party build tools. */
    FOREIGN_BUILD_FILE("1. Inga tredjepartsberoenden", "no files from Maven, Gradle or other third-party build tools");

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
