package spola.demo;

import java.io.Serial;

/** A line of the order file is broken. The message names the file and the line number. */
final class OrderFileException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int line;

    OrderFileException(String file, int line, String problem) {
        super(file + " line " + line + ": " + problem);
        this.line = line;
    }

    /** The broken line's number, from 1. */
    int line() {
        return line;
    }
}
