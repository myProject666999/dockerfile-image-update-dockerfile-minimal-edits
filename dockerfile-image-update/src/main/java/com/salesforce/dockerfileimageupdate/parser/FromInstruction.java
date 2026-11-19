package com.salesforce.dockerfileimageupdate.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A parsed {@code FROM} instruction.
 *
 * <p>Everything is structured: the image name, tag and digest, the {@code --platform} flag, the
 * stage alias, and where the tag value comes from. The origin matters: when the tag is a variable
 * whose value comes from an {@code ARG} default, the updater edits that default and leaves the
 * {@code FROM} line as a variable reference, so that build-time {@code --build-arg} overrides keep
 * working.</p>
 */
public final class FromInstruction extends Instruction {

    /** What the image token of the {@code FROM} refers to. */
    public enum ReferenceKind {
        /**
         * A named image that may be updated. For a whole-token variable reference
         * ({@code FROM $BASE} or {@code FROM ${BASE}}), the name comes from an ARG default and
         * {@link FromInstruction#getImageResolution()} says where.
         */
        IMAGE,
        /** The Dockerfile's special empty base image; never updated. */
        SCRATCH,
        /** A stage declared earlier in the same file; never updated. */
        STAGE,
        /** The image token could not be resolved statically, or the line is not safe to edit. */
        UNRESOLVED
    }

    /** Where the tag value that would be updated comes from. */
    public enum TagOrigin {
        /** Literal tag text on the {@code FROM} line (including a {@code ${VAR:-default}} default). */
        FROM_LINE,
        /** Default value of an {@code ARG} declared before the first {@code FROM}. */
        GLOBAL_ARG_DEFAULT,
        /** No usable tag: untagged, a stage, scratch, or an unresolvable value. */
        NONE
    }

    private final String platformText;
    private final TextSpan platformSpan;
    private final VariableExpression platformVariable;
    private final Token imageToken;
    private final ImageReference imageReference;
    private final VariableExpression imageVariable;
    private final VariableExpression tagVariable;
    private final String stageAlias;
    private final ReferenceKind referenceKind;
    private final FromInstruction referencedStage;
    private final TagOrigin tagOrigin;
    private final ArgumentResolver.Resolution tagResolution;
    private final ArgumentResolver.Resolution imageResolution;
    private final TextSpan tagInsertionPoint;
    private final List<Diagnostic> diagnostics;

    FromInstruction(LogicalLine line, String platformText, TextSpan platformSpan,
                    VariableExpression platformVariable, Token imageToken,
                    ImageReference imageReference, VariableExpression imageVariable,
                    VariableExpression tagVariable, String stageAlias, ReferenceKind referenceKind,
                    FromInstruction referencedStage, TagOrigin tagOrigin,
                    ArgumentResolver.Resolution tagResolution, ArgumentResolver.Resolution imageResolution,
                    TextSpan tagInsertionPoint, List<Diagnostic> diagnostics) {
        super(line);
        this.platformText = platformText;
        this.platformSpan = platformSpan;
        this.platformVariable = platformVariable;
        this.imageToken = imageToken;
        this.imageReference = imageReference;
        this.imageVariable = imageVariable;
        this.tagVariable = tagVariable;
        this.stageAlias = stageAlias;
        this.referenceKind = referenceKind;
        this.referencedStage = referencedStage;
        this.tagOrigin = tagOrigin;
        this.tagResolution = tagResolution;
        this.imageResolution = imageResolution;
        this.tagInsertionPoint = tagInsertionPoint;
        this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
    }

    FromInstruction withTagResolution(TagOrigin newOrigin, ArgumentResolver.Resolution newResolution,
                                      List<Diagnostic> extraDiagnostics) {
        List<Diagnostic> merged = new ArrayList<>(diagnostics);
        merged.addAll(extraDiagnostics);
        return new FromInstruction(getLine(), platformText, platformSpan, platformVariable, imageToken,
                imageReference, imageVariable, tagVariable, stageAlias, referenceKind, referencedStage,
                newOrigin, newResolution, imageResolution, tagInsertionPoint, merged);
    }

    FromInstruction withImageResolution(TagOrigin newOrigin, ArgumentResolver.Resolution newImageResolution,
                                        List<Diagnostic> extraDiagnostics) {
        List<Diagnostic> merged = new ArrayList<>(diagnostics);
        merged.addAll(extraDiagnostics);
        return new FromInstruction(getLine(), platformText, platformSpan, platformVariable, imageToken,
                imageReference, imageVariable, tagVariable, stageAlias, referenceKind, referencedStage,
                newOrigin, tagResolution, newImageResolution, tagInsertionPoint, merged);
    }

    /** @return the {@code --platform} value as written, or {@code null}. */
    public String getPlatformText() {
        return platformText;
    }

    /** @return the span of the platform value, or {@code null}. */
    public TextSpan getPlatformSpan() {
        return platformSpan;
    }

    /** @return the variable expression in the platform value, or {@code null} when literal/absent. */
    public VariableExpression getPlatformVariable() {
        return platformVariable;
    }

    /** @return the parsed image reference, or {@code null} when the token could not be parsed. */
    public ImageReference getImageReference() {
        return imageReference;
    }

    /** @return the variable expression the whole image token consists of, or {@code null}. */
    public VariableExpression getImageVariable() {
        return imageVariable;
    }

    /** @return the variable expression inside the tag position, or {@code null} when literal/absent. */
    public VariableExpression getTagVariable() {
        return tagVariable;
    }

    public String getStageAlias() {
        return stageAlias;
    }

    public boolean hasStageAlias() {
        return stageAlias != null;
    }

    public ReferenceKind getReferenceKind() {
        return referenceKind;
    }

    /** @return the earlier stage this FROM refers to, or {@code null}. */
    public FromInstruction getReferencedStage() {
        return referencedStage;
    }

    public TagOrigin getTagOrigin() {
        return tagOrigin;
    }

    public ArgumentResolver.Resolution getTagResolution() {
        return tagResolution;
    }

    /**
     * @return for a whole-token variable in the image position ({@code FROM $BASE}), how that
     * variable resolves; otherwise {@code null}.
     */
    public ArgumentResolver.Resolution getImageResolution() {
        return imageResolution;
    }

    /** @return whether the image name itself came from a variable's default value. */
    public boolean hasVariableImage() {
        return imageVariable != null;
    }

    /**
     * @return the offset at which a tag must be inserted to tag an otherwise untagged literal
     * image ({@code FROM image} to {@code FROM image:newtag}), or {@code null} when insertion is
     * not applicable or not safe.
     */
    public TextSpan getTagInsertionPoint() {
        return tagInsertionPoint;
    }

    /**
     * @return the tag value currently in effect (possibly an ARG default or an inline default),
     * or {@code null} when no tag value can be determined statically.
     */
    public String getEffectiveTagText() {
        switch (tagOrigin) {
            case FROM_LINE:
                if (tagVariable != null) {
                    if (tagVariable.getForm() == VariableExpression.Form.INLINE_DEFAULT) {
                        return tagVariable.getInlineDefault();
                    }
                    if (tagVariable.getForm() == VariableExpression.Form.NO_DEFAULT) {
                        return null; // only known at build time
                    }
                    return null;
                }
                return imageReference == null ? null : imageReference.getTagText();
            case GLOBAL_ARG_DEFAULT:
                if (imageVariable != null) {
                    // The whole image token is a variable; the effective tag is the tag part of
                    // the ARG default (if the default carries one).
                    String imageDefault = imageResolution == null ? null : imageResolution.getValue();
                    return tagOf(imageDefault);
                }
                return tagResolution == null ? null : tagResolution.getValue();
            case NONE:
            default:
                return null;
        }
    }

    private static String tagOf(String reference) {
        if (reference == null) {
            return null;
        }
        try {
            return ImageReference.parse(reference).getTagText();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean hasEffectiveTag() {
        return getEffectiveTagText() != null;
    }

    /**
     * @return the span of the exact bytes that hold the tag value and must be replaced when the
     * tag changes, or {@code null} when this instruction must not be edited.
     */
    public TextSpan getTagValueSpan() {
        switch (tagOrigin) {
            case FROM_LINE:
                if (imageReference == null || imageToken == null) {
                    return null;
                }
                TextSpan tagSpan = imageReference.getTagSpan();
                if (tagSpan == null) {
                    return null;
                }
                int from = tagSpan.getStart();
                int to = tagSpan.getEnd();
                if (tagVariable != null) {
                    if (tagVariable.getForm() != VariableExpression.Form.INLINE_DEFAULT) {
                        return null;
                    }
                    from += tagVariable.getInlineDefaultStart();
                    to = tagSpan.getStart() + tagVariable.getInlineDefaultEnd();
                }
                return imageToken.spanOf(from, to);
            case GLOBAL_ARG_DEFAULT:
                if (imageVariable != null) {
                    return variableImageTagSpan();
                }
                return tagResolution == null ? null : tagResolution.getUpdateSpan();
            case NONE:
            default:
                return null;
        }
    }

    /**
     * Locates the tag inside an ARG default that supplies a whole image reference, e.g.
     * {@code ARG BASE=alpine:3.19} referenced by {@code FROM $BASE}.
     */
    private TextSpan variableImageTagSpan() {
        if (imageResolution == null || imageResolution.getUpdateSpan() == null
                || imageResolution.getValue() == null) {
            return null;
        }
        ImageReference resolved;
        try {
            resolved = ImageReference.parse(imageResolution.getValue());
        } catch (IllegalArgumentException e) {
            return null;
        }
        TextSpan tagSpan = resolved.getTagSpan();
        if (tagSpan == null) {
            return null;
        }
        int start = imageResolution.getUpdateSpan().getStart();
        return new TextSpan(start + tagSpan.getStart(), start + tagSpan.getEnd());
    }

    /**
     * @return whether this instruction names an external image the updater could consider. It says
     * nothing about whether the tag is statically known; see {@link #getTagOrigin()}.
     */
    public boolean isUpdatableImage() {
        return referenceKind == ReferenceKind.IMAGE && (imageReference != null || imageVariable != null);
    }

    /**
     * @return the image name to match against the image being updated. For a literal reference
     * this is the reference name; for a whole-token variable it is the name from the ARG default.
     */
    public String getEffectiveImageName() {
        if (imageReference != null) {
            return imageReference.getName();
        }
        if (imageVariable != null && imageResolution != null && imageResolution.getValue() != null) {
            try {
                return ImageReference.parse(imageResolution.getValue()).getName();
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    /** @return the reference text actually in effect, for messages. */
    public String getDisplayReference() {
        if (imageReference != null) {
            return imageReference.getRaw();
        }
        if (imageResolution != null && imageResolution.getValue() != null) {
            return imageResolution.getValue();
        }
        return imageVariable == null ? null : imageVariable.getRaw();
    }

    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }
}
