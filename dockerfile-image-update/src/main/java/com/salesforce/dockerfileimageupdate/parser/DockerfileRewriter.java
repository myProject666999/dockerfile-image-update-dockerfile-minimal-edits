package com.salesforce.dockerfileimageupdate.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites the tag of one image inside a Dockerfile, touching only the bytes of the tag value
 * itself. Everything else &mdash; line terminators, trailing whitespace, comment indentation, the
 * presence or absence of a trailing newline, and any byte-order mark &mdash; is copied through
 * untouched.
 *
 * <p>When the tag to update comes from an {@code ARG} default, the default is edited and the
 * {@code FROM} line keeps its variable reference, so {@code --build-arg} overrides still win.
 * When a value cannot be determined statically, the reference is skipped with a reported reason
 * and the rest of the file is still processed.</p>
 *
 * <p>{@code docker-compose} style {@code image: name:tag} lines are handled the same way when
 * requested, also by span editing, so a single rewrite pass can serve both file kinds.</p>
 */
public final class DockerfileRewriter {

    static final String DEFAULT_IGNORE_MARKER = "no-dfiu";

    private DockerfileRewriter() {
    }

    public static RewriteResult rewrite(DockerfileDocument document, String imageToFind,
                                        String newTag, String ignoreMarker) {
        return rewrite(document, imageToFind, newTag, ignoreMarker, false);
    }

    /**
     * @param document      parsed document to rewrite
     * @param imageToFind   image name to match; a reference matches when its name ends with this
     *                      string, which preserves the tool's historic matching behaviour
     * @param newTag        tag to apply
     * @param ignoreMarker  comment text (without {@code #}) on the line immediately before a
     *                      reference that suppresses the update; blank means {@code no-dfiu}
     * @param includeCompose whether to also rewrite {@code image: name:tag} lines
     */
    public static RewriteResult rewrite(DockerfileDocument document, String imageToFind,
                                        String newTag, String ignoreMarker, boolean includeCompose) {
        String text = document.getText();
        List<RewriteResult.Edit> edits = new ArrayList<>();
        List<Diagnostic> skipped = new ArrayList<>();

        if (imageToFind == null || newTag == null || newTag.isEmpty()) {
            return new RewriteResult(text, text, edits, skipped);
        }

        List<LogicalLine> lines = document.getLines();
        for (FromInstruction from : document.getFromInstructions()) {
            if (!from.isUpdatableImage()) {
                continue;
            }
            String display = from.getDisplayReference();
            String imageName = from.getEffectiveImageName();
            if (!matches(imageName, imageToFind)) {
                continue;
            }
            if (isIgnored(lines, from.getLine(), ignoreMarker)) {
                skipped.add(Diagnostic.info(from.getLineNumber(),
                        "Skipped '" + display + "' because the preceding comment requests it"));
                continue;
            }

            String effectiveTag = from.getEffectiveTagText();
            boolean insertTag = false;
            if (effectiveTag == null) {
                if (!from.hasVariableImage() && from.getTagInsertionPoint() != null
                        && from.getImageReference() != null && !from.getImageReference().hasTag()) {
                    // Untagged literal image: the tag is appended, matching the tool's historical
                    // behaviour of turning "FROM image" into "FROM image:newtag".
                    insertTag = true;
                } else {
                    skipped.add(Diagnostic.warning(from.getLineNumber(),
                            "Skipped '" + display + "' because its tag cannot be determined statically"));
                    continue;
                }
            } else if (effectiveTag.equals(newTag)) {
                continue;
            }

            TextSpan span;
            if (insertTag) {
                span = from.getTagInsertionPoint();
            } else {
                span = from.getTagValueSpan();
            }
            if (span == null) {
                skipped.add(Diagnostic.warning(from.getLineNumber(),
                        "Skipped '" + display + "' because its tag value is not a safe contiguous span"));
                continue;
            }
            String newValue = insertTag ? ":" + newTag : newTag;
            String description = from.getTagOrigin() == FromInstruction.TagOrigin.GLOBAL_ARG_DEFAULT
                    ? "ARG default for " + imageName
                    : (insertTag ? "inserted tag for " + imageName : "FROM tag for " + imageName);
            edits.add(new RewriteResult.Edit(span, effectiveTag == null ? "" : effectiveTag,
                    newValue, description));
        }

        if (includeCompose) {
            collectComposeEdits(lines, imageToFind, newTag, ignoreMarker, edits, skipped);
        }

        String updated = applyEdits(text, edits);
        return new RewriteResult(text, updated, edits, skipped);
    }

    private static void collectComposeEdits(List<LogicalLine> lines, String imageToFind, String newTag,
                                            String ignoreMarker, List<RewriteResult.Edit> edits,
                                            List<Diagnostic> skipped) {
        for (int i = 0; i < lines.size(); i++) {
            LogicalLine line = lines.get(i);
            if (line.isComment() || line.isBlank() || line.getTokens().isEmpty()) {
                continue;
            }
            Token valueToken = composeValueToken(line);
            if (valueToken == null) {
                continue;
            }
            String value = valueToken.getText();
            int hash = value.indexOf('#');
            String referenceText = hash >= 0 ? value.substring(0, hash) : value;
            ImageReference reference;
            try {
                reference = ImageReference.parse(referenceText);
            } catch (IllegalArgumentException e) {
                skipped.add(Diagnostic.warning(line.getLineNumber(),
                        "Skipped compose image value '" + referenceText + "': " + e.getMessage()));
                continue;
            }
            if (!matches(reference.getName(), imageToFind)) {
                continue;
            }
            if (isIgnored(lines, line, ignoreMarker)) {
                skipped.add(Diagnostic.info(line.getLineNumber(),
                        "Skipped compose image '" + reference.getRaw() + "' because the preceding comment requests it"));
                continue;
            }
            if (!reference.hasTag()) {
                skipped.add(Diagnostic.info(line.getLineNumber(),
                        "Skipped compose image '" + reference.getRaw() + "' because it has no tag to update"));
                continue;
            }
            if (!isRewritableTag(reference.getTagText())) {
                skipped.add(Diagnostic.info(line.getLineNumber(),
                        "Skipped compose image '" + reference.getRaw() + "' because its tag is not a value this tool updates"));
                continue;
            }
            if (reference.getTagText().equals(newTag)) {
                continue;
            }
            TextSpan tagSpan = reference.getTagSpan();
            TextSpan documentSpan = valueToken.spanOf(tagSpan.getStart(), tagSpan.getEnd());
            if (documentSpan == null) {
                skipped.add(Diagnostic.warning(line.getLineNumber(),
                        "Skipped compose image '" + reference.getRaw() + "' because the value is split across lines"));
                continue;
            }
            edits.add(new RewriteResult.Edit(documentSpan, reference.getTagText(), newTag,
                    "compose image tag for " + reference.getName()));
        }
    }

    /**
     * Finds the value token of an {@code image: name:tag} line. Both {@code image: name:tag} and
     * {@code image:name:tag} (no space after the colon) are accepted; the returned token's text
     * excludes the {@code image:} key and its offsets point at the value in the document.
     */
    private static Token composeValueToken(LogicalLine line) {
        List<Token> tokens = line.getTokens();
        Token first = tokens.get(0);
        String text = first.getText();
        if (!text.regionMatches(true, 0, "image:", 0, "image:".length())) {
            return null;
        }
        if (text.length() == "image:".length()) {
            return tokens.size() > 1 ? tokens.get(1) : null;
        }
        int valueStart = "image:".length();
        while (valueStart < text.length() && CharRun.isSeparator(text.charAt(valueStart))) {
            valueStart++;
        }
        if (valueStart >= text.length()) {
            return tokens.size() > 1 ? tokens.get(1) : null;
        }
        return first.suffix(valueStart);
    }

    /**
     * Compose tags are only updated when they look like a concrete tag: a non-empty value of
     * letters, digits, {@code _}, {@code .} and {@code -}, and not {@code latest}. This is the
     * same rule the tool has always applied to compose files.
     */
    static boolean isRewritableTag(String tag) {
        if (tag == null || tag.isEmpty() || "latest".equals(tag)) {
            return false;
        }
        for (int i = 0; i < tag.length(); i++) {
            char c = tag.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-')) {
                return false;
            }
        }
        return Character.isLetterOrDigit(tag.charAt(0)) || tag.charAt(0) == '_';
    }

    private static boolean matches(String imageName, String imageToFind) {
        // Historic semantics: the reference name ends with the searched name, so
        // "registry:5000/team/image" matches "image" but "my-image" does not match "image".
        return imageName != null && imageToFind != null && imageName.endsWith(imageToFind);
    }

    /**
     * The ignore marker may be a whole comment line immediately before the reference (the form
     * every historical test uses) or a trailing comment on the same line (the form the README
     * documents for {@code FROM}). Both are checked; anything else is not an ignore request.
     */
    private static boolean isIgnored(List<LogicalLine> lines, LogicalLine line, String ignoreMarker) {
        String expected = ignoreMarker == null || ignoreMarker.trim().isEmpty()
                ? DEFAULT_IGNORE_MARKER
                : ignoreMarker.trim();
        if (hasTrailingMarker(line, expected)) {
            return true;
        }
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).getStartOffset() != line.getStartOffset()) {
                continue;
            }
            if (i == 0) {
                return false;
            }
            LogicalLine previous = lines.get(i - 1);
            if (!previous.isComment()) {
                return false;
            }
            String body = previous.getText();
            int hash = body.indexOf('#');
            if (hash < 0) {
                return false;
            }
            return body.substring(hash + 1).trim().equalsIgnoreCase(expected);
        }
        return false;
    }

    private static boolean hasTrailingMarker(LogicalLine line, String expected) {
        String text = line.getText();
        int hash = text.indexOf('#');
        if (hash < 0) {
            return false;
        }
        return text.substring(hash + 1).trim().equalsIgnoreCase(expected);
    }

    static String applyEdits(String text, List<RewriteResult.Edit> edits) {
        if (edits.isEmpty()) {
            return text;
        }
        List<RewriteResult.Edit> sorted = new ArrayList<>(edits);
        sorted.sort((a, b) -> Integer.compare(a.getSpan().getStart(), b.getSpan().getStart()));
        StringBuilder out = new StringBuilder(text.length());
        int cursor = 0;
        for (RewriteResult.Edit edit : sorted) {
            TextSpan span = edit.getSpan();
            if (span.getStart() < cursor) {
                throw new IllegalStateException("Overlapping edits at " + span);
            }
            out.append(text, cursor, span.getStart());
            out.append(edit.getNewValue());
            cursor = span.getEnd();
        }
        out.append(text, cursor, text.length());
        return out.toString();
    }
}
