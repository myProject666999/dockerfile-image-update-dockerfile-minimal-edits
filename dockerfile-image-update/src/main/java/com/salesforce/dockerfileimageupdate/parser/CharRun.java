package com.salesforce.dockerfileimageupdate.parser;

import java.util.Arrays;

/**
 * A run of characters that may have been assembled from several disjoint pieces of the original
 * document (a logical line joined across backslash continuations). Every character carries the
 * offset it had in the document, so spans can be mapped back exactly. The synthetic separator
 * inserted between joined physical lines carries the offset of the line break that was swallowed
 * and only ever serves as a token boundary; it is never part of a token.
 */
final class CharRun {
    private final StringBuilder text = new StringBuilder();
    private int[] offsets = new int[32];
    private int size;

    void append(char c, int offset) {
        text.append(c);
        if (size == offsets.length) {
            offsets = Arrays.copyOf(offsets, offsets.length * 2);
        }
        offsets[size++] = offset;
    }

    int length() {
        return size;
    }

    char charAt(int index) {
        return text.charAt(index);
    }

    Token subToken(int start, int end) {
        int[] tokenOffsets = Arrays.copyOfRange(offsets, start, end);
        return new Token(text.substring(start, end), tokenOffsets);
    }

    static boolean isSeparator(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f' || c == 0x0B;
    }
}
