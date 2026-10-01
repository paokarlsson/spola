/**
 * Machine verification of the ground rules in {@code framework/REGLER.md}.
 *
 * <p>The module checks source code (Compiler Tree API), bytecode (Class-File API),
 * module dependencies and foreign files, and reports every violation with file, line
 * (for source) and the rule that was broken. Entry points:
 * <ul>
 *   <li>{@link spola.guardrails.Guardrails} for use from Java code,</li>
 *   <li>{@link spola.guardrails.GuardrailsTool}, a {@link java.util.spi.ToolProvider}
 *       named {@code spola-guardrails} that the build runs on every build.</li>
 * </ul>
 */
module spola.guardrails {
    requires jdk.compiler;

    exports spola.guardrails;

    provides java.util.spi.ToolProvider with spola.guardrails.GuardrailsTool;
}
