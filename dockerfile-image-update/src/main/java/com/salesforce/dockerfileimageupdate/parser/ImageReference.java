package com.salesforce.dockerfileimageupdate.parser;

import java.util.Objects;

/**
 * A Docker image reference as it appears in a {@code FROM} instruction:
 * {@code [registry[:port]/]path[/name][:tag][@digest]}.
 *
 * <p>The tag separator is the colon in the final path segment, not the first colon in the
 * string. A private registry with a port ({@code registry.example.com:5000/team/image:1.2})
 * therefore parses to name {@code registry.example.com:5000/team/image} and tag {@code 1.2}.</p>
 */
public final class ImageReference {
    private final String raw;
    private final String name;
    private final String tagText;
    private final TextSpan tagSpan;
    private final String digest;
    private final TextSpan digestSpan;

    private ImageReference(String raw, String name, String tagText, TextSpan tagSpan,
                           String digest, TextSpan digestSpan) {
        this.raw = raw;
        this.name = name;
        this.tagText = tagText;
        this.tagSpan = tagSpan;
        this.digest = digest;
        this.digestSpan = digestSpan;
    }

    /**
     * Parses a reference token.
     *
     * @throws IllegalArgumentException when the token cannot be a reference at all (empty, or more
     *                                  than one {@code @}). The parser catches this and reports a
     *                                  diagnostic; it never escapes from {@link DockerfileParser}.
     */
    public static ImageReference parse(String token) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("Empty image reference");
        }
        int at = token.indexOf('@');
        if (at >= 0 && token.indexOf('@', at + 1) >= 0) {
            throw new IllegalArgumentException("Image reference has more than one '@': " + token);
        }
        String nameAndTag = at >= 0 ? token.substring(0, at) : token;
        String digest = at >= 0 ? token.substring(at + 1) : null;
        TextSpan digestSpan = at >= 0 ? new TextSpan(at + 1, token.length()) : null;
        if (digest != null && digest.isEmpty()) {
            throw new IllegalArgumentException("Empty digest in image reference: " + token);
        }
        if (nameAndTag.isEmpty()) {
            throw new IllegalArgumentException("Missing image name: " + token);
        }

        int lastSlash = nameAndTag.lastIndexOf('/');
        int colon = tagSeparatorIndex(nameAndTag, lastSlash);
        String name = nameAndTag;
        String tag = null;
        TextSpan tagSpan = null;
        if (colon > lastSlash) {
            name = nameAndTag.substring(0, colon);
            tag = nameAndTag.substring(colon + 1);
            tagSpan = new TextSpan(colon + 1, nameAndTag.length());
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Missing image name: " + token);
            }
        }
        return new ImageReference(token, name, tag, tagSpan, digest, digestSpan);
    }

    /**
     * The tag separator is the first colon after the final path segment that is not inside a
     * {@code ${...}} expansion. Taking the first rather than the last colon keeps
     * {@code alpine:${BASE:-3.19}} intact (the {@code :-} operator is not a separator) and matches
     * how the tool has always read {@code image:tag:extra}: tag {@code tag}.
     */
    private static int tagSeparatorIndex(String nameAndTag, int lastSlash) {
        int braceDepth = 0;
        for (int i = lastSlash + 1; i < nameAndTag.length(); i++) {
            char c = nameAndTag.charAt(i);
            if (c == '{') {
                braceDepth++;
            } else if (c == '}' && braceDepth > 0) {
                braceDepth--;
            } else if (c == ':' && braceDepth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** @return the complete token as written. */
    public String getRaw() {
        return raw;
    }

    /** @return the image name including registry and namespace, without tag or digest. */
    public String getName() {
        return name;
    }

    /** @return the literal tag text as written, or {@code null} when there is no tag. */
    public String getTagText() {
        return tagText;
    }

    /** @return span of the tag text inside {@link #getRaw()}, or {@code null} when untagged. */
    public TextSpan getTagSpan() {
        return tagSpan;
    }

    public boolean hasTag() {
        return tagSpan != null;
    }

    /** @return the digest text after {@code @}, or {@code null}. */
    public String getDigest() {
        return digest;
    }

    /** @return span of the digest inside {@link #getRaw()}, or {@code null}. */
    public TextSpan getDigestSpan() {
        return digestSpan;
    }

    public boolean hasDigest() {
        return digest != null;
    }

    /** @return whether the reference carries no tag (and therefore cannot be updated in place). */
    public boolean isUntagged() {
        return tagSpan == null;
    }

    @Override
    public String toString() {
        return raw;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ImageReference)) {
            return false;
        }
        ImageReference that = (ImageReference) other;
        return raw.equals(that.raw) && name.equals(that.name)
                && Objects.equals(tagText, that.tagText) && Objects.equals(digest, that.digest);
    }

    @Override
    public int hashCode() {
        return Objects.hash(raw, name, tagText, digest);
    }
}
