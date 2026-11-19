package com.salesforce.dockerfileimageupdate.parser;

/** An instruction the updater does not act on. Kept so stage and ARG scoping stays consistent. */
public final class OtherInstruction extends Instruction {
    OtherInstruction(LogicalLine line) {
        super(line);
    }
}
