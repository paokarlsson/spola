package spola.codegen;

import java.io.Serial;
import java.nio.file.Path;

/**
 * A record's with-interface cannot be generated, or a generated file cannot be written. The
 * message names the source file and the record, and says what to change; it is meant to be
 * shown as it is, without a stack trace.
 */
public final class CodegenException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    CodegenException(String message) {
        super(message);
    }

    /** {@code <file>: cannot generate <Record>With for record <Record>: <reason>}. */
    static CodegenException of(Path file, String recordName, String reason) {
        return new CodegenException(file + ": cannot generate " + RecordCodegen.interfaceName(recordName)
                + " for record " + recordName + ": " + reason);
    }
}
