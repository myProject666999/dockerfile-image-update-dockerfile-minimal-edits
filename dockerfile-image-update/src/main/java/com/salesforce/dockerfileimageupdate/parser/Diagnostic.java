package com.salesforce.dockerfileimageupdate.parser;

import java.util.Objects;

/**
 * A reason the parser or rewriter did not treat something as an updatable reference.
 * Diagnostics never abort parsing: the rest of the file keeps being processed.
 */
public final class Diagnostic {
    public enum Severity {
        /** The file or line could not be handled and was left untouched. */
        WARNING,
        /** Informational, e.g. a stage reference or {@code scratch} was correctly recognised. */
        INFO
    }

    private final Severity severity;
    private final int lineNumber;
    private final String message;

    public Diagnostic(Severity severity, int lineNumber, String message) {
        this.severity = Objects.requireNonNull(severity);
        this.message = Objects.requireNonNull(message);
        this.lineNumber = lineNumber;
    }

    public static Diagnostic warning(int lineNumber, String message) {
        return new Diagnostic(Severity.WARNING, lineNumber, message);
    }

    public static Diagnostic info(int lineNumber, String message) {
        return new Diagnostic(Severity.INFO, lineNumber, message);
    }

    public Severity getSeverity() {
        return severity;
    }

    /** @return 1-based physical line number the diagnostic refers to, or 0 if file-wide. */
    public int getLineNumber() {
        return lineNumber;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return (lineNumber > 0 ? "line " + lineNumber + ": " : "") + severity + ": " + message;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Diagnostic)) {
            return false;
        }
        Diagnostic that = (Diagnostic) other;
        return severity == that.severity && lineNumber == that.lineNumber && message.equals(that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(severity, lineNumber, message);
    }
}
