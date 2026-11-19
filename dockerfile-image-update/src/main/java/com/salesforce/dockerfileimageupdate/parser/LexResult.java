package com.salesforce.dockerfileimageupdate.parser;

import java.util.List;

/**
 * A namespace-compatible container for the result of lexing.
 */
public final class LexResult {
    private final String text;
    private final List<LogicalLine> lines;
    private final List<Diagnostic> diagnostics;

    LexResult(String text, List<LogicalLine> lines, List<Diagnostic> diagnostics) {
        this.text = text;
        this.lines = lines;
        this.diagnostics = diagnostics;
    }

    public String getText() {
        return text;
    }

    public List<LogicalLine> getLines() {
        return lines;
    }

    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }
}
