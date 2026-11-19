package com.salesforce.dockerfileimageupdate.parser;

import java.util.Locale;

/**
 * Resolves which {@code ARG} declaration a variable reference in a given {@code FROM} should use,
 * following the Dockerfile reference's scoping rules:
 * <ul>
 *     <li>{@code ARG} declared before the first {@code FROM} is available to {@code FROM} lines
 *     (global scope). It is not visible inside any stage unless re-declared.</li>
 *     <li>An {@code ARG} re-declared inside a stage shadows the global declaration from that point
 *     on, for that stage only.</li>
 *     <li>When the same name is declared more than once in the same scope, the last declaration
 *     wins.</li>
 * </ul>
 */
public final class ArgumentResolver {

    public enum Status {
        /** A declaration with a usable literal default was found. */
        RESOLVED,
        /** No declaration was found in scope; the value comes only from the build command line. */
        UNDECLARED,
        /** Declarations exist in scope but none has a default value. */
        NO_DEFAULT,
        /** The in-scope declaration's default value cannot be used statically. */
        UNSUPPORTED
    }

    /** Outcome of resolving a variable name at a point in the file. */
    public static final class Resolution {
        private final String variableName;
        private final Status status;
        private final String value;
        private final TextSpan updateSpan;
        private final ArgInstruction declaration;
        private final String detail;

        Resolution(String variableName, Status status, String value, TextSpan updateSpan,
                   ArgInstruction declaration, String detail) {
            this.variableName = variableName;
            this.status = status;
            this.value = value;
            this.updateSpan = updateSpan;
            this.declaration = declaration;
            this.detail = detail;
        }

        public String getVariableName() {
            return variableName;
        }

        public Status getStatus() {
            return status;
        }

        /** @return the literal value when {@link #getStatus()} is {@link Status#RESOLVED}. */
        public String getValue() {
            return value;
        }

        /**
         * @return the span in the document of the bytes to replace when the value changes (the
         * {@code ARG} default value), or {@code null} when there is nothing safe to replace.
         */
        public TextSpan getUpdateSpan() {
            return updateSpan;
        }

        /** @return the declaration actually in effect, or {@code null}. */
        public ArgInstruction getDeclaration() {
            return declaration;
        }

        public String getDetail() {
            return detail;
        }

        public boolean isResolved() {
            return status == Status.RESOLVED;
        }

        @Override
        public String toString() {
            return "Resolution{" + variableName + ", " + status
                    + (value != null ? ", value=" + value : "")
                    + (declaration != null ? ", line=" + declaration.getLineNumber() : "")
                    + (detail != null ? ", " + detail : "") + "}";
        }
    }

    private final DockerfileDocument document;

    public ArgumentResolver(DockerfileDocument document) {
        this.document = document;
    }

    /**
     * Resolves {@code variableName} as seen from the {@code FROM} instruction at
     * {@code fromStageIndex}.
     *
     * <p>Docker resolves the image token of a {@code FROM} using global-scope ARGs. A variable
     * that appears only in a stage-level declaration is therefore not what the {@code FROM} used;
     * it is reported as undeclared here so the line is left alone.</p>
     */
    public Resolution resolveForFrom(String variableName, int fromStageIndex) {
        ArgInstruction global = null;
        for (ArgInstruction arg : document.getArgInstructions()) {
            if (arg.isGlobalScope() && variableName.equals(arg.getName())) {
                global = arg; // last wins within a scope
            }
        }
        if (global != null) {
            return fromDeclaration(variableName, global);
        }
        ArgInstruction staged = null;
        for (ArgInstruction arg : document.getArgInstructions()) {
            if (!arg.isGlobalScope() && variableName.equals(arg.getName())) {
                staged = arg;
            }
        }
        if (staged != null) {
            return new Resolution(variableName, Status.UNDECLARED, null, null, null,
                    "ARG '" + variableName + "' is only declared inside a stage (line "
                            + staged.getLineNumber() + "); FROM lines only see global ARGs");
        }
        return new Resolution(variableName, Status.UNDECLARED, null, null, null,
                "ARG '" + variableName + "' is not declared in the file; its value can only come from the build command line");
    }

    /**
     * Resolves {@code variableName} inside the stage at {@code stageIndex}, honouring a stage-level
     * re-declaration. This mirrors Docker's in-stage variable scope.
     */
    public Resolution resolveForStage(String variableName, int stageIndex) {
        ArgInstruction staged = null;
        for (ArgInstruction arg : document.getArgInstructions()) {
            if (!arg.isGlobalScope() && arg.getStageIndex() == stageIndex
                    && variableName.equals(arg.getName())) {
                staged = arg;
            }
        }
        if (staged != null) {
            return fromDeclaration(variableName, staged);
        }
        return resolveForFrom(variableName, stageIndex);
    }

    private static Resolution fromDeclaration(String variableName, ArgInstruction declaration) {
        if (declaration.hasMultipleDeclarations()) {
            return new Resolution(variableName, Status.UNSUPPORTED, null, null, declaration,
                    "ARG declaration at line " + declaration.getLineNumber()
                            + " declares more than one variable");
        }
        if (declaration.getName() == null || !declaration.getName().equals(variableName)) {
            return new Resolution(variableName, Status.UNSUPPORTED, null, null, declaration,
                    "ARG declaration at line " + declaration.getLineNumber() + " is not usable");
        }
        if (!declaration.hasDefaultValue()) {
            return new Resolution(variableName, Status.NO_DEFAULT, null, null, declaration,
                    "ARG '" + variableName + "' at line " + declaration.getLineNumber()
                            + " has no default value");
        }
        VariableExpression defaultExpression = VariableExpression.parse(declaration.getDefaultValue());
        if (defaultExpression != null) {
            return new Resolution(variableName, Status.UNSUPPORTED, null, null, declaration,
                    "default value of ARG '" + variableName + "' at line " + declaration.getLineNumber()
                            + " is itself a variable");
        }
        TextSpan span = declaration.contiguousDefaultValueSpan();
        if (span == null) {
            return new Resolution(variableName, Status.UNSUPPORTED, null, null, declaration,
                    "default value of ARG '" + variableName + "' at line " + declaration.getLineNumber()
                            + " is split across lines and cannot be rewritten safely");
        }
        return new Resolution(variableName, Status.RESOLVED, declaration.getDefaultValue(), span,
                declaration, null);
    }
}
