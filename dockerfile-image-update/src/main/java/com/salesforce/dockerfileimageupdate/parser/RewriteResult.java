package com.salesforce.dockerfileimageupdate.parser;

import java.util.List;
import java.util.Objects;

/**
 * The result of rewriting a Dockerfile. When {@link #isModified()} is {@code false},
 * {@link #getUpdatedText()} is guaranteed identical ({@link String#equals}) to the input.
 */
public final class RewriteResult {
    private final String originalText;
    private final String updatedText;
    private final List<Edit> edits;
    private final List<Diagnostic> skipped;

    RewriteResult(String originalText, String updatedText, List<Edit> edits, List<Diagnostic> skipped) {
        this.originalText = originalText;
        this.updatedText = updatedText;
        this.edits = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(edits));
        this.skipped = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(skipped));
    }

    public String getOriginalText() {
        return originalText;
    }

    public String getUpdatedText() {
        return updatedText;
    }

    public boolean isModified() {
        return !originalText.equals(updatedText);
    }

    /** @return the byte/character edits that were applied, in document order. */
    public List<Edit> getEdits() {
        return edits;
    }

    /** @return diagnostics for references that matched but were deliberately not rewritten. */
    public List<Diagnostic> getSkipped() {
        return skipped;
    }

    /**
     * @return the offset of the first character that differs between the input and the output,
     * or -1 when they are identical.
     */
    public int firstDifferenceOffset() {
        int limit = Math.min(originalText.length(), updatedText.length());
        for (int i = 0; i < limit; i++) {
            if (originalText.charAt(i) != updatedText.charAt(i)) {
                return i;
            }
        }
        if (originalText.length() == updatedText.length()) {
            return -1;
        }
        return limit;
    }

    /** A single byte-range replacement performed by the rewriter. */
    public static final class Edit {
        private final TextSpan span;
        private final String originalValue;
        private final String newValue;
        private final String description;

        public Edit(TextSpan span, String originalValue, String newValue, String description) {
            this.span = Objects.requireNonNull(span);
            this.originalValue = originalValue;
            this.newValue = newValue;
            this.description = description;
        }

        public TextSpan getSpan() {
            return span;
        }

        public String getOriginalValue() {
            return originalValue;
        }

        public String getNewValue() {
            return newValue;
        }

        public String getDescription() {
            return description;
        }

        @Override
        public String toString() {
            return description + " " + span + ": '" + originalValue + "' -> '" + newValue + "'";
        }
    }
}
