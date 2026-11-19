package com.salesforce.dockerfileimageupdate.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses a Dockerfile into {@link Instruction}s with source spans, without ever modifying the
 * input. Anything that cannot be understood becomes a {@link Diagnostic} and leaves the affected
 * instruction untouched instead of aborting the whole file.
 */
public final class DockerfileParser {

    private DockerfileParser() {
    }

    /**
     * Parses raw file bytes. Content must be valid UTF-8 (an optional UTF-8 byte-order mark is
     * fine and preserved). UTF-16 and undecodable input are rejected with an
     * {@link UnsupportedDockerfileEncodingException} rather than silently decoded or transcoded.
     *
     * <p>Decoding as UTF-8 and re-encoding the result reproduces the original bytes exactly,
     * including the byte-order mark, which is what makes byte-for-byte preservation possible.</p>
     */
    public static DockerfileDocument parse(byte[] bytes) throws UnsupportedDockerfileEncodingException {
        if (bytes.length >= 2) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            if ((b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)) {
                throw new UnsupportedDockerfileEncodingException(
                        "File is UTF-16 encoded (byte-order mark "
                                + String.format("%02X %02X", b0, b1)
                                + "); refusing to transcode. Save it as UTF-8 to update it.");
            }
        }
        for (byte b : bytes) {
            if (b == 0) {
                throw new UnsupportedDockerfileEncodingException(
                        "File contains NUL bytes, which indicates UTF-16 or binary content; refusing to transcode.");
            }
        }
        java.nio.charset.CharsetDecoder decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        String text;
        try {
            text = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new UnsupportedDockerfileEncodingException(
                    "File is not valid UTF-8; refusing to transcode it. Save it as UTF-8 to update it.");
        }
        return parseText(text);
    }

    /** Parses already-decoded text. Never throws. */
    public static DockerfileDocument parseText(String text) {
        String safeText = text == null ? "" : text;
        LexResult lexed = DockerfileLexer.lex(safeText);
        List<Diagnostic> diagnostics = new ArrayList<>(lexed.getDiagnostics());

        EscapeDirective escape = readEscapeDirective(lexed.getLines());
        if (escape != null && escape.character != '\\') {
            diagnostics.add(Diagnostic.warning(escape.lineNumber,
                    "Parser directive '# escape=" + escape.character
                            + "' is not supported; the file was left unchanged to avoid corrupting it."));
            return new DockerfileDocument(safeText, lexed.getLines(), Collections.emptyList(), diagnostics);
        }

        List<Instruction> instructions = new ArrayList<>();
        List<FromInstruction> stages = new ArrayList<>();
        List<Diagnostic> instructionDiagnostics = new ArrayList<>();
        int currentStageIndex = -1;

        for (LogicalLine line : lexed.getLines()) {
            if (line.isComment() || line.isBlank()) {
                continue;
            }
            switch (line.getKeyword()) {
                case "from":
                    FromInstruction from = parseFrom(line, stages, instructionDiagnostics);
                    instructions.add(from);
                    stages.add(from);
                    currentStageIndex++;
                    break;
                case "arg":
                    instructions.add(parseArg(line, currentStageIndex, instructionDiagnostics));
                    break;
                default:
                    instructions.add(new OtherInstruction(line));
                    break;
            }
        }
        diagnostics.addAll(instructionDiagnostics);

        DockerfileDocument preliminary = new DockerfileDocument(safeText, instructions, diagnostics);
        List<Diagnostic> resolutionDiagnostics = new ArrayList<>();
        List<FromInstruction> resolvedStages = resolveTagOrigins(preliminary, stages, resolutionDiagnostics);
        // Replace FROM entries with their resolved forms, preserving instruction order.
        List<Instruction> resolved = new ArrayList<>(instructions.size());
        int stageCursor = 0;
        for (Instruction instruction : instructions) {
            if (instruction instanceof FromInstruction) {
                resolved.add(resolvedStages.get(stageCursor++));
            } else {
                resolved.add(instruction);
            }
        }
        diagnostics.addAll(resolutionDiagnostics);
        return new DockerfileDocument(safeText, lexed.getLines(), resolved, diagnostics);
    }

    private static FromInstruction parseFrom(LogicalLine line, List<FromInstruction> priorStages,
                                             List<Diagnostic> diagnostics) {
        List<Diagnostic> local = new ArrayList<>();
        LineContent content = LineContent.splitAtComment(line);
        List<Token> tokens = content.tokens;

        String platformText = null;
        TextSpan platformSpan = null;
        VariableExpression platformVariable = null;
        boolean unsupported = false;

        int index = 1; // token 0 is the keyword
        while (index < tokens.size() && tokens.get(index).getText().startsWith("--")) {
            Token flag = tokens.get(index);
            String flagText = flag.getText();
            int eq = flagText.indexOf('=');
            if (eq < 0) {
                local.add(Diagnostic.warning(line.getLineNumber(),
                        "FROM flag '" + flagText + "' has no value; line left unchanged"));
                unsupported = true;
            } else if (!"platform".equalsIgnoreCase(flagText.substring(2, eq))) {
                local.add(Diagnostic.warning(line.getLineNumber(),
                        "FROM flag '--" + flagText.substring(2, eq) + "' is not supported; line left unchanged"));
                unsupported = true;
            } else {
                platformText = flagText.substring(eq + 1);
                platformSpan = flagValueSpan(flag, eq + 1);
                platformVariable = VariableExpression.parse(platformText);
                if (platformVariable != null && !platformVariable.isSupported()) {
                    // The platform value is never edited and does not affect tag positions, so an
                    // expansion we cannot interpret is only reported; the tag stays updatable.
                    local.add(Diagnostic.warning(line.getLineNumber(),
                            "Cannot interpret --platform value '" + platformText + "': "
                                    + platformVariable.getProblem() + "; the tag is still updated"));
                }
            }
            index++;
        }

        if (index >= tokens.size()) {
            local.add(Diagnostic.warning(line.getLineNumber(),
                    "FROM instruction has no image reference; line left unchanged"));
            diagnostics.addAll(local);
            return new FromInstruction(line, platformText, platformSpan, platformVariable, null, null,
                    null, null, null, FromInstruction.ReferenceKind.UNRESOLVED, null,
                    FromInstruction.TagOrigin.NONE, null, null, null, local);
        }

        Token imageToken = tokens.get(index++);
        String imageText = imageToken.getText();
        String alias = null;

        if (index < tokens.size()) {
            Token asToken = tokens.get(index);
            if (!"as".equalsIgnoreCase(asToken.getText())) {
                local.add(Diagnostic.warning(line.getLineNumber(),
                        "Unexpected token '" + asToken.getText()
                                + "' in FROM instruction (stage aliases require 'AS'); line left unchanged"));
                unsupported = true;
                index = tokens.size();
            } else if (index + 1 >= tokens.size()) {
                local.add(Diagnostic.warning(line.getLineNumber(),
                        "FROM has 'AS' without a stage name; line left unchanged"));
                unsupported = true;
                index = tokens.size();
            } else {
                alias = tokens.get(index + 1).getText();
                index += 2;
            }
        }
        if (index < tokens.size()) {
            local.add(Diagnostic.warning(line.getLineNumber(),
                    "Unexpected trailing tokens in FROM instruction; line left unchanged"));
            unsupported = true;
        }

        // Only a token that *is* a variable (nothing but the reference) is treated as a variable
        // image; "name:${TAG}" is a literal name with a variable tag and goes through
        // ImageReference parsing below.
        VariableExpression imageVariable =
                imageText.startsWith("$") ? VariableExpression.parse(imageText) : null;
        ImageReference reference = null;
        VariableExpression tagVariable = null;
        FromInstruction.ReferenceKind kind = FromInstruction.ReferenceKind.UNRESOLVED;
        FromInstruction referencedStage = null;
        TextSpan tagInsertionPoint = null;

        if (imageVariable != null) {
            if (!imageVariable.isSupported()) {
                local.add(Diagnostic.warning(line.getLineNumber(),
                        "Unsupported variable reference in image '" + imageText + "': "
                                + imageVariable.getProblem() + "; line left unchanged"));
            } else if (imageVariable.getForm() == VariableExpression.Form.INLINE_DEFAULT) {
                local.add(Diagnostic.warning(line.getLineNumber(),
                        "Image specified with an inline-default variable ('" + imageText
                                + "') cannot be matched to a concrete image name; line left unchanged"));
            } else {
                // Whole-token variable (FROM $BASE / FROM ${BASE}); its value may come from an
                // ARG default and is resolved in the second pass. Mark it updatable-IMAGE for now.
                kind = FromInstruction.ReferenceKind.IMAGE;
            }
        } else {
            try {
                reference = ImageReference.parse(imageText);
                kind = FromInstruction.ReferenceKind.IMAGE;
            } catch (IllegalArgumentException e) {
                local.add(Diagnostic.warning(line.getLineNumber(),
                        "Could not parse image reference '" + imageText + "': " + e.getMessage()
                                + "; line left unchanged"));
            }
        }

        if (kind == FromInstruction.ReferenceKind.IMAGE && reference != null) {
            if ("scratch".equalsIgnoreCase(reference.getName())) {
                kind = FromInstruction.ReferenceKind.SCRATCH;
            } else {
                FromInstruction stage = findStage(priorStages, reference.getRaw());
                if (stage != null) {
                    kind = FromInstruction.ReferenceKind.STAGE;
                    referencedStage = stage;
                } else if (reference.hasTag()) {
                    tagVariable = VariableExpression.parse(reference.getTagText());
                    if (tagVariable != null && !tagVariable.isSupported()) {
                        local.add(Diagnostic.warning(line.getLineNumber(),
                                "Unsupported variable in tag '" + reference.getTagText() + "': "
                                        + tagVariable.getProblem() + "; line left unchanged"));
                        unsupported = true;
                    }
                } else if (!reference.hasDigest()) {
                    // Untagged and not digest-pinned: a tag can be appended. A digest-pinned
                    // reference is left exactly as written.
                    tagInsertionPoint = tagInsertionPoint(imageToken, reference);
                }
            }
        }

        if (unsupported) {
            kind = FromInstruction.ReferenceKind.UNRESOLVED;
        }

        FromInstruction.TagOrigin origin = FromInstruction.TagOrigin.NONE;
        if (kind == FromInstruction.ReferenceKind.IMAGE) {
            if (reference != null && (tagVariable != null || reference.hasTag())) {
                origin = FromInstruction.TagOrigin.FROM_LINE;
                // A bare variable tag has no literal value to rewrite; resolution may replace this.
            }
        }

        diagnostics.addAll(local);
        return new FromInstruction(line, platformText, platformSpan, platformVariable, imageToken,
                reference, imageVariable, tagVariable, alias, kind, referencedStage, origin, null,
                null, tagInsertionPoint, local);
    }

    /**
     * @return the document offset at which a tag may be inserted after an untagged literal image
     * name, provided the image token is one contiguous run; otherwise {@code null}.
     */
    private static TextSpan tagInsertionPoint(Token imageToken, ImageReference reference) {
        int nameLength = reference.getName().length();
        return imageToken.spanOf(nameLength, nameLength);
    }

    private static TextSpan flagValueSpan(Token flag, int valueStartInFlagText) {
        return flag.spanOf(valueStartInFlagText, flag.length());
    }

    private static FromInstruction findStage(List<FromInstruction> priorStages, String name) {
        // Later declarations shadow earlier ones; a stage alias also shadows an external image of
        // the same name, per the Dockerfile reference.
        for (int i = priorStages.size() - 1; i >= 0; i--) {
            FromInstruction stage = priorStages.get(i);
            if (stage.getStageAlias() != null && stage.getStageAlias().equalsIgnoreCase(name)) {
                return stage;
            }
        }
        return null;
    }

    private static ArgInstruction parseArg(LogicalLine line, int stageIndex, List<Diagnostic> diagnostics) {
        List<Diagnostic> local = new ArrayList<>();
        List<Token> tokens = LineContent.splitAtComment(line).tokens;
        if (tokens.size() < 2) {
            local.add(Diagnostic.warning(line.getLineNumber(),
                    "ARG declaration has no variable name; line left unchanged"));
            diagnostics.addAll(local);
            return new ArgInstruction(line, null, null, null, false, false, false, local, stageIndex);
        }
        if (tokens.size() > 2) {
            // Dockerfile versions before 1.4 do not accept multiple declarations on one ARG
            // line, and this tool targets that grammar; refuse instead of guessing.
            local.add(Diagnostic.warning(line.getLineNumber(),
                    "ARG line declares more than one variable, which the supported Dockerfile versions do not allow; line left unchanged"));
            diagnostics.addAll(local);
            String firstText = tokens.get(1).getText();
            int eq = firstText.indexOf('=');
            String name = eq >= 0 ? firstText.substring(0, eq) : firstText;
            return new ArgInstruction(line, name, null, null, eq >= 0, false, true, local, stageIndex);
        }

        Token token = tokens.get(1);
        String text = token.getText();
        int eq = text.indexOf('=');
        if (eq < 0) {
            if (!isValidVariableName(text)) {
                local.add(Diagnostic.warning(line.getLineNumber(),
                        "ARG name '" + text + "' is not a valid variable name; line left unchanged"));
            }
            diagnostics.addAll(local);
            return new ArgInstruction(line, text, null, null, false, false, false, local, stageIndex);
        }
        String name = text.substring(0, eq);
        String defaultValue = text.substring(eq + 1);
        TextSpan defaultValueSpan = token.spanOf(eq + 1, text.length());
        if (!isValidVariableName(name)) {
            local.add(Diagnostic.warning(line.getLineNumber(),
                    "ARG name '" + name + "' is not a valid variable name; line left unchanged"));
        }
        if (defaultValue.isEmpty()) {
            local.add(Diagnostic.warning(line.getLineNumber(),
                    "ARG '" + name + "' has an empty default value"));
            defaultValueSpan = null;
        }
        diagnostics.addAll(local);
        return new ArgInstruction(line, name, defaultValue, defaultValueSpan, true, true, false, local, stageIndex);
    }

    private static boolean isValidVariableName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        char first = name.charAt(0);
        if (!(Character.isLetter(first) || first == '_')) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) {
                return false;
            }
        }
        return true;
    }

    /**
     * Second pass: resolve each variable tag through the argument scopes and record where the
     * value actually comes from. Done after all ARGs are known so that a later re-declaration can
     * be attributed correctly.
     */
    private static List<FromInstruction> resolveTagOrigins(DockerfileDocument document,
                                                           List<FromInstruction> stages,
                                                           List<Diagnostic> diagnostics) {
        ArgumentResolver resolver = new ArgumentResolver(document);
        List<FromInstruction> result = new ArrayList<>(stages.size());
        for (int i = 0; i < stages.size(); i++) {
            FromInstruction from = stages.get(i);
            if (from.getReferenceKind() != FromInstruction.ReferenceKind.IMAGE) {
                result.add(from);
                continue;
            }
            if (from.hasVariableImage()) {
                result.add(resolveVariableImage(from, i, resolver, diagnostics));
                continue;
            }
            VariableExpression tagVariable = from.getTagVariable();
            if (tagVariable == null) {
                result.add(from);
                continue;
            }
            if (!tagVariable.isSupported()) {
                result.add(from);
                continue;
            }
            if (tagVariable.getForm() == VariableExpression.Form.INLINE_DEFAULT) {
                // Default already appears on the FROM line; FROM_LINE origin is correct.
                result.add(from);
                continue;
            }
            ArgumentResolver.Resolution resolution = resolver.resolveForFrom(tagVariable.getName(), i);
            List<Diagnostic> extra = new ArrayList<>();
            if (resolution.isResolved()) {
                result.add(from.withTagResolution(FromInstruction.TagOrigin.GLOBAL_ARG_DEFAULT,
                        resolution, extra));
            } else {
                extra.add(Diagnostic.warning(from.getLineNumber(),
                        "Cannot statically determine the tag of '" + from.getImageReference().getName()
                                + "' because ARG '" + tagVariable.getName() + "' "
                                + describeUnresolved(resolution) + "; line left unchanged"));
                result.add(from.withTagResolution(FromInstruction.TagOrigin.NONE, resolution, extra));
            }
            diagnostics.addAll(extra);
        }
        return result;
    }

    /**
     * Resolves a {@code FROM $BASE}-style image token through the ARG scopes. The reference name
     * comes from the ARG default; a default without a tag yields no updatable tag.
     */
    private static FromInstruction resolveVariableImage(FromInstruction from, int stageIndex,
                                                        ArgumentResolver resolver,
                                                        List<Diagnostic> diagnostics) {
        VariableExpression imageVariable = from.getImageVariable();
        List<Diagnostic> extra = new ArrayList<>();
        ArgumentResolver.Resolution resolution =
                resolver.resolveForFrom(imageVariable.getName(), stageIndex);
        if (!resolution.isResolved()) {
            extra.add(Diagnostic.warning(from.getLineNumber(),
                    "Cannot statically determine which image '" + imageVariable.getRaw()
                            + "' refers to because ARG '" + imageVariable.getName() + "' "
                            + describeUnresolved(resolution) + "; line left unchanged"));
            diagnostics.addAll(extra);
            return from.withImageResolution(FromInstruction.TagOrigin.NONE, resolution, extra);
        }
        ImageReference resolvedReference;
        try {
            resolvedReference = ImageReference.parse(resolution.getValue());
        } catch (IllegalArgumentException e) {
            extra.add(Diagnostic.warning(from.getLineNumber(),
                    "Default value of ARG '" + imageVariable.getName() + "' ('" + resolution.getValue()
                            + "') is not a valid image reference; line left unchanged"));
            diagnostics.addAll(extra);
            return from.withImageResolution(FromInstruction.TagOrigin.NONE, resolution, extra);
        }
        if ("scratch".equalsIgnoreCase(resolvedReference.getName())) {
            extra.add(Diagnostic.info(from.getLineNumber(),
                    "Image '" + imageVariable.getRaw() + "' resolves to 'scratch'; nothing to update"));
            diagnostics.addAll(extra);
            return from.withImageResolution(FromInstruction.TagOrigin.NONE, resolution, extra);
        }
        if (!resolvedReference.hasTag()) {
            extra.add(Diagnostic.warning(from.getLineNumber(),
                    "Default value of ARG '" + imageVariable.getName() + "' ('" + resolution.getValue()
                            + "') has no tag to update; line left unchanged"));
            diagnostics.addAll(extra);
            return from.withImageResolution(FromInstruction.TagOrigin.NONE, resolution, extra);
        }
        diagnostics.addAll(extra);
        return from.withImageResolution(FromInstruction.TagOrigin.GLOBAL_ARG_DEFAULT, resolution, extra);
    }

    private static String describeUnresolved(ArgumentResolver.Resolution resolution) {
        switch (resolution.getStatus()) {
            case UNDECLARED:
                return "is not declared in this file";
            case NO_DEFAULT:
                return "is declared without a default value";
            case UNSUPPORTED:
                return "has a default value that cannot be resolved statically";
            default:
                return "cannot be resolved";
        }
    }

    private static EscapeDirective readEscapeDirective(List<LogicalLine> lines) {
        for (LogicalLine line : lines) {
            if (!line.isComment()) {
                return null;
            }
            String trimmed = line.getText().trim();
            if (!trimmed.startsWith("#")) {
                continue;
            }
            String body = trimmed.substring(1).trim();
            if (body.toLowerCase(java.util.Locale.ROOT).startsWith("escape=")) {
                String value = body.substring("escape=".length()).trim();
                return new EscapeDirective(value.length() == 1 ? value.charAt(0) : '\0',
                        line.getLineNumber());
            }
        }
        return null;
    }

    private static final class EscapeDirective {
        private final char character;
        private final int lineNumber;

        private EscapeDirective(char character, int lineNumber) {
            this.character = character;
            this.lineNumber = lineNumber;
        }
    }

    /** Tokens of an instruction line with any {@code #} comment removed. */
    static final class LineContent {
        final List<Token> tokens;

        private LineContent(List<Token> tokens) {
            this.tokens = tokens;
        }

        static LineContent splitAtComment(LogicalLine line) {
            List<Token> kept = new ArrayList<>();
            for (Token token : line.getTokens()) {
                int hash = token.getText().indexOf('#');
                if (hash < 0) {
                    kept.add(token);
                } else {
                    if (hash > 0) {
                        kept.add(token.prefix(hash));
                    }
                    break;
                }
            }
            return new LineContent(Collections.unmodifiableList(kept));
        }
    }
}
