package com.salesforce.dockerfileimageupdate.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Splits Dockerfile text into logical lines without changing a single character. Handles:
 * <ul>
 *     <li>both {@code LF} and {@code CRLF} terminators, including files that mix them;</li>
 *     <li>backslash line continuations, with comment lines inside a continuation skipped;</li>
 *     <li>comment and blank lines;</li>
 *     <li>instruction keywords in any case ({@code from}, {@code From}, {@code FROM});</li>
 *     <li>a UTF-8 byte-order mark, preserved and kept out of the first keyword.</li>
 * </ul>
 * Nothing here throws on unusual content; the caller inspects the produced lines. UTF-16 is
 * rejected before lexing (see {@link DockerfileParser}) because it cannot be preserved without
 * transcoding.
 */
public final class DockerfileLexer {

    static final char BYTE_ORDER_MARK = (char) 0xFEFF;

    private DockerfileLexer() {
    }

    public static LexResult lex(String text) {
        List<LogicalLine> lines = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        int length = text.length();
        boolean startsWithBom = length > 0 && text.charAt(0) == BYTE_ORDER_MARK;
        int lineNumber = 1;
        int cursor = 0;

        while (cursor < length) {
            int lineStart = cursor;
            int logicalLineNumber = lineNumber;
            CharRun run = new CharRun();
            int lineEnd = cursor;
            boolean commentLine = false;
            boolean firstSegment = true;

            while (true) {
                int contentEnd = endOfPhysicalLine(text, cursor);
                int contentStart = skipIndent(text, cursor, contentEnd);
                if (firstSegment && cursor == 0 && startsWithBom && contentStart == 0) {
                    contentStart = 1;
                }
                boolean isComment = contentStart < contentEnd && text.charAt(contentStart) == '#';

                if (isComment) {
                    if (firstSegment) {
                        commentLine = true;
                        lineEnd = trimTrailing(text, cursor, contentEnd);
                        cursor = advancePastTerminator(text, contentEnd);
                        lineNumber++;
                        break;
                    }
                    // Comment lines inside a continuation are ignored and do not end it.
                    cursor = advancePastTerminator(text, contentEnd);
                    lineNumber++;
                    if (cursor >= length) {
                        break;
                    }
                    continue;
                }

                boolean continues = continuationBackslash(text, cursor, contentEnd) >= 0;
                // Whitespace before a trailing backslash is significant: the backslash and the
                // line break vanish, but a space before them still separates tokens.
                int contentStop = continues
                        ? continuationBackslash(text, cursor, contentEnd)
                        : trimTrailing(text, cursor, contentEnd);

                if (contentStart >= contentStop && !firstSegment) {
                    // A blank or whitespace-only physical line ends the continuation.
                    cursor = advancePastTerminator(text, contentEnd);
                    lineNumber++;
                    break;
                }
                for (int k = contentStart; k < contentStop; k++) {
                    run.append(text.charAt(k), k);
                }
                if (contentStop > contentStart) {
                    lineEnd = contentStop;
                }

                cursor = advancePastTerminator(text, contentEnd);
                lineNumber++;
                firstSegment = false;
                if (!continues || cursor >= length) {
                    break;
                }
            }

            String lineText = text.substring(lineStart, lineEnd);
            List<Token> tokens = Token.split(run);
            String keyword = tokens.isEmpty() ? null : tokens.get(0).getText().toLowerCase(Locale.ROOT);
            boolean blank = tokens.isEmpty() && !commentLine;
            lines.add(new LogicalLine(lineStart, lineEnd, logicalLineNumber, lineText,
                    commentLine, blank, tokens, keyword));
        }

        if (startsWithBom) {
            diagnostics.add(Diagnostic.info(1,
                    "File starts with a UTF-8 byte-order mark; it is preserved byte-for-byte."));
        }
        return new LexResult(text, lines, diagnostics);
    }

    private static int endOfPhysicalLine(String text, int start) {
        int i = start;
        while (i < text.length() && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
            i++;
        }
        return i;
    }

    private static int skipIndent(String text, int start, int end) {
        int i = start;
        while (i < end && CharRun.isSeparator(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int trimTrailing(String text, int start, int end) {
        int i = end;
        while (i > start && CharRun.isSeparator(text.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    private static int continuationBackslash(String text, int start, int end) {
        int i = end - 1;
        while (i >= start && CharRun.isSeparator(text.charAt(i))) {
            i--;
        }
        return i >= start && text.charAt(i) == '\\' ? i : -1;
    }

    private static int advancePastTerminator(String text, int contentEnd) {
        if (contentEnd >= text.length()) {
            return text.length();
        }
        if (text.charAt(contentEnd) == '\r' && contentEnd + 1 < text.length()
                && text.charAt(contentEnd + 1) == '\n') {
            return contentEnd + 2;
        }
        return contentEnd + 1;
    }
}
