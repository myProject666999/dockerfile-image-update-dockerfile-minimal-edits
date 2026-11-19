package com.salesforce.dockerfileimageupdate.parser;

import java.util.Objects;

/**
 * A half-open range {@code [start, end)} of character offsets in a document. Offsets are
 * character offsets; for the ASCII Dockerfile content this tool rewrites they are also byte
 * offsets, and the rewriter never transcodes or re-encodes content, so the two stay aligned.
 */
public final class TextSpan {
    private final int start;
    private final int end;

    public TextSpan(int start, int end) {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Invalid span [" + start + ", " + end + ")");
        }
        this.start = start;
        this.end = end;
    }

    public int getStart() {
        return start;
    }

    public int getEnd() {
        return end;
    }

    public int length() {
        return end - start;
    }

    public boolean contains(int offset) {
        return offset >= start && offset < end;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TextSpan)) {
            return false;
        }
        TextSpan that = (TextSpan) other;
        return start == that.start && end == that.end;
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, end);
    }

    @Override
    public String toString() {
        return "[" + start + ", " + end + ")";
    }
}
