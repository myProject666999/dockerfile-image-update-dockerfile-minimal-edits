package com.salesforce.dockerfileimageupdate.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A parsed Dockerfile. Parsing never throws on unusual input; anything that could not be
 * understood is reported through {@link #getDiagnostics()} while the rest of the document is
 * still parsed.
 */
public final class DockerfileDocument {
    private final String text;
    private final List<LogicalLine> lines;
    private final List<Instruction> instructions;
    private final List<Diagnostic> diagnostics;

    public DockerfileDocument(String text, List<Instruction> instructions, List<Diagnostic> diagnostics) {
        this(text, Collections.emptyList(), instructions, diagnostics);
    }

    public DockerfileDocument(String text, List<LogicalLine> lines, List<Instruction> instructions,
                              List<Diagnostic> diagnostics) {
        this.text = text;
        this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
        this.instructions = Collections.unmodifiableList(new ArrayList<>(instructions));
        this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
    }

    public String getText() {
        return text;
    }

    /** @return every logical line in file order, comments and blanks included. */
    public List<LogicalLine> getLines() {
        return lines;
    }

    public List<Instruction> getInstructions() {
        return instructions;
    }

    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }

    /**
     * @return every {@code FROM} instruction in file order, one entry per instruction. Stages that
     * repeat a name each appear; which stage a reference points at is recorded on the instruction.
     */
    public List<FromInstruction> getFromInstructions() {
        List<FromInstruction> result = new ArrayList<>();
        for (Instruction instruction : instructions) {
            if (instruction instanceof FromInstruction) {
                result.add((FromInstruction) instruction);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * @return every {@link ArgInstruction} in file order, including re-declarations, so callers can
     * apply last-wins scoping themselves.
     */
    public List<ArgInstruction> getArgInstructions() {
        List<ArgInstruction> result = new ArrayList<>();
        for (Instruction instruction : instructions) {
            if (instruction instanceof ArgInstruction) {
                result.add((ArgInstruction) instruction);
            }
        }
        return Collections.unmodifiableList(result);
    }
}
