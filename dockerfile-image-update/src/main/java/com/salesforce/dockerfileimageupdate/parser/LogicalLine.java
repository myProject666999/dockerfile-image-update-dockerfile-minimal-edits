package com.salesforce.dockerfileimageupdate.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A logical Dockerfile line: one physical line, or several joined by backslash continuations.
 * The span covers the first character of the first physical line through the last content
 * character of the last physical line. Line terminators are never part of the span, so rewriting
 * by span can never touch a {@code \n} or {@code \r\n}.
 */
public final class LogicalLine {
    private final int startOffset;
    @SuppressWarnings("unused") // part of the line model; useful to callers mapping spans
    private final int endOffset;
    private final int lineNumber;
    private final String text;
    private final boolean comment;
    private final boolean blank;
    private final List<Token> tokens;
    private final String keyword;

    LogicalLine(int startOffset, int endOffset, int lineNumber, String text,
                boolean comment, boolean blank, List<Token> tokens, String keyword) {
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.lineNumber = lineNumber;
        this.text = text;
        this.comment = comment;
        this.blank = blank;
        this.tokens = Collections.unmodifiableList(tokens);
        this.keyword = keyword;
    }

    /** Offset of the first character of the first physical line. */
    public int getStartOffset() {
        return startOffset;
    }

    /** @return 1-based physical line number where this logical line starts. */
    public int getLineNumber() {
        return lineNumber;
    }

    /** @return the exact document text covered by this logical line (continuations included). */
    public String getText() {
        return text;
    }

    public boolean isComment() {
        return comment;
    }

    public boolean isBlank() {
        return blank;
    }

    /** @return whitespace-split tokens; empty for comments and blank lines. */
    public List<Token> getTokens() {
        return tokens;
    }

    /** @return the lower-cased instruction keyword, or {@code null} when there is no token. */
    public String getKeyword() {
        return keyword;
    }

}
