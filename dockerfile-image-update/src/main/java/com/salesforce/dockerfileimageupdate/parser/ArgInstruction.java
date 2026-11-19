package com.salesforce.dockerfileimageupdate.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An {@code ARG} declaration. The default value, when present, is recorded together with its
 * span, because updating an image whose tag comes from an ARG must edit the declaration's default
 * rather than hard-coding the tag into {@code FROM}.
 */
public final class ArgInstruction extends Instruction {
    private final String name;
    private final String defaultValue;
    private final TextSpan defaultValueSpan;
    private final boolean hasEqualsSign;
    private final boolean hasDefaultValue;
    private final boolean multipleDeclarations;
    private final List<Diagnostic> diagnostics;
    /** Index of the {@code FROM} instruction that precedes this ARG, or -1 before the first FROM. */
    private final int stageIndex;

    ArgInstruction(LogicalLine line, String name, String defaultValue, TextSpan defaultValueSpan,
                   boolean hasEqualsSign, boolean hasDefaultValue, boolean multipleDeclarations,
                   List<Diagnostic> diagnostics, int stageIndex) {
        super(line);
        this.name = name;
        this.defaultValue = defaultValue;
        this.defaultValueSpan = defaultValueSpan;
        this.hasEqualsSign = hasEqualsSign;
        this.hasDefaultValue = hasDefaultValue;
        this.multipleDeclarations = multipleDeclarations;
        this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
        this.stageIndex = stageIndex;
    }

    public String getName() {
        return name;
    }

    /** @return the literal default value, or {@code null} when the declaration has none. */
    public String getDefaultValue() {
        return defaultValue;
    }

    /** @return span of the default value text, or {@code null} when there is none. */
    public TextSpan getDefaultValueSpan() {
        return defaultValueSpan;
    }

    /** @return the default value span only when it is contiguous and safe to rewrite. */
    TextSpan contiguousDefaultValueSpan() {
        return defaultValueSpan;
    }

    public boolean hasDefaultValue() {
        return hasDefaultValue;
    }

    /** @return whether the declaration used the {@code ARG name=default} form. */
    public boolean hasEqualsSign() {
        return hasEqualsSign;
    }

    /**
     * @return {@code true} when one declaration line carried more than one {@code name=value}
     * pair, which the supported Dockerfile versions do not allow.
     */
    public boolean hasMultipleDeclarations() {
        return multipleDeclarations;
    }

    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }

    /**
     * @return index into {@link DockerfileDocument#getFromInstructions()} of the stage this
     * declaration sits in, or -1 when it appears before the first {@code FROM}.
     */
    public int getStageIndex() {
        return stageIndex;
    }

    /** @return whether this declaration is at global scope (before any {@code FROM}). */
    public boolean isGlobalScope() {
        return stageIndex < 0;
    }
}
