package com.salesforce.dockerfileimageupdate.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * A single whitespace-delimited token of a logical Dockerfile instruction. Each character of the
 * token remembers the offset it came from in the original document, which is what lets the
 * rewriter make a byte-exact replacement: a token that was split across line continuations has
 * non-contiguous offsets and therefore cannot be edited safely, and is reported as such instead
 * of being rewritten.
 */
final class Token {
    private final String text;
    private final int[] offsets;

    Token(String text, int[] offsets) {
        this.text = text;
        this.offsets = offsets;
    }

    String getText() {
        return text;
    }

    int length() {
        return text.length();
    }

    /**
     * @return the document span occupied by this token, or {@code null} when the token is not a
     * single contiguous run of characters in the document (e.g. it was stitched together across a
     * line continuation).
     */
    TextSpan getSpan() {
        return spanOf(0, text.length());
    }

    /**
     * @return the document span holding characters {@code [from, to)} of this token, or
     * {@code null} when that sub-range is not one contiguous run in the document (for example the
     * token was split across a line continuation). Callers use this to map a substring, such as a
     * tag, back to the exact bytes that hold it.
     */
    TextSpan spanOf(int from, int to) {
        if (to <= from) {
            int point = from < text.length() ? offsets[from] : offsets[text.length() - 1] + 1;
            return new TextSpan(point, point);
        }
        int start = offsets[from];
        for (int i = from + 1; i < to; i++) {
            if (offsets[i] != start + (i - from)) {
                return null;
            }
        }
        return new TextSpan(start, start + (to - from));
    }



    /**
     * @return the first {@code length} characters as a token, preserving offsets. Used to strip a
     * trailing {@code #comment} that was glued to the token with no whitespace.
     */
    Token prefix(int length) {
        return new Token(text.substring(0, length), java.util.Arrays.copyOf(offsets, length));
    }

    /** @return the token from {@code start} onwards, keeping the document offsets. */
    Token suffix(int start) {
        return new Token(text.substring(start), java.util.Arrays.copyOfRange(offsets, start, offsets.length));
    }

    static List<Token> split(CharRun run) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int length = run.length();
        while (i < length) {
            while (i < length && CharRun.isSeparator(run.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < length && !CharRun.isSeparator(run.charAt(i))) {
                i++;
            }
            if (i > start) {
                tokens.add(run.subToken(start, i));
            }
        }
        return tokens;
    }
}
