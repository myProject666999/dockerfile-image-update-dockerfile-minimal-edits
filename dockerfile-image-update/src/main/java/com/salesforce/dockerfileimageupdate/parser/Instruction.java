package com.salesforce.dockerfileimageupdate.parser;

/**
 * One logical instruction in a Dockerfile. Instructions that the updater does not care about are
 * still represented so that stage/arg scoping and offsets stay consistent.
 */
public abstract class Instruction {
    private final LogicalLine line;

    protected Instruction(LogicalLine line) {
        this.line = line;
    }

    public LogicalLine getLine() {
        return line;
    }

    /** @return 1-based physical line number where the instruction starts. */
    public int getLineNumber() {
        return line.getLineNumber();
    }

    /** @return the lower-cased keyword, e.g. {@code from}. */
    public String getKeyword() {
        return line.getKeyword();
    }
}
