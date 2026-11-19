package com.salesforce.dockerfileimageupdate.parser;

import java.util.Objects;

/**
 * A variable reference in a Dockerfile value. Supported forms, per the Dockerfile reference:
 * <ul>
 *     <li>{@code $NAME} and {@code ${NAME}} &mdash; no default;</li>
 *     <li>{@code ${NAME:-default}} &mdash; inline default used when the variable is unset or
 *     empty.</li>
 * </ul>
 * Every other expansion ({@code ${NAME-default}}, {@code ${NAME:+alt}}, {@code ${NAME:?err}},
 * nested expressions, or a variable concatenated with literal text) is recognised but marked
 * {@link Form#UNSUPPORTED}, so the caller skips the line with a clear reason instead of rewriting
 * it. This is the documented support boundary.
 */
public final class VariableExpression {

    public enum Form {
        /** {@code $NAME} or {@code ${NAME}}. */
        NO_DEFAULT,
        /** {@code ${NAME:-default}}. */
        INLINE_DEFAULT,
        /** A variable reference outside the supported subset. */
        UNSUPPORTED
    }

    private final Form form;
    private final String raw;
    private final String name;
    private final String inlineDefault;
    private final int inlineDefaultStart;
    private final int inlineDefaultEnd;
    private final String problem;

    private VariableExpression(Form form, String raw, String name, String inlineDefault,
                               int inlineDefaultStart, int inlineDefaultEnd, String problem) {
        this.form = form;
        this.raw = raw;
        this.name = name;
        this.inlineDefault = inlineDefault;
        this.inlineDefaultStart = inlineDefaultStart;
        this.inlineDefaultEnd = inlineDefaultEnd;
        this.problem = problem;
    }

    /**
     * Parses {@code value}. Returns {@code null} when the value contains no {@code $} and is
     * therefore a literal. Never throws: unsupported shapes come back as
     * {@link Form#UNSUPPORTED} with {@link #getProblem()} set.
     */
    public static VariableExpression parse(String value) {
        if (value == null || value.indexOf('$') < 0) {
            return null;
        }
        if (value.startsWith("${")) {
            if (!value.endsWith("}")) {
                return unsupported(value, "unterminated variable reference");
            }
            String body = value.substring(2, value.length() - 1);
            int operator = indexOfOperator(body);
            if (operator < 0) {
                if (body.indexOf('$') >= 0) {
                    return unsupported(value, "nested variable reference");
                }
                if (!isValidName(body)) {
                    return unsupported(value, "invalid variable name '" + body + "'");
                }
                return new VariableExpression(Form.NO_DEFAULT, value, body, null, -1, -1, null);
            }
            String name = body.substring(0, operator);
            if (!isValidName(name)) {
                return unsupported(value, "invalid variable name '" + name + "'");
            }
            char operatorChar = body.charAt(operator);
            boolean withColon = operator + 1 < body.length() && body.charAt(operator + 1) == '-';
            if (operatorChar != ':' || !withColon) {
                return unsupported(value, "unsupported variable expansion '" + value
                        + "' (only ${NAME} and ${NAME:-default} are supported)");
            }
            String defaultValue = body.substring(operator + 2);
            if (defaultValue.indexOf('$') >= 0) {
                return unsupported(value, "nested variable reference in default value");
            }
            // value = "${" + body + "}"; body starts at index 2, default starts at 2+operator+2.
            int defaultStart = 2 + operator + 2;
            return new VariableExpression(Form.INLINE_DEFAULT, value, name, defaultValue,
                    defaultStart, defaultStart + defaultValue.length(), null);
        }
        if (value.startsWith("$")) {
            String name = value.substring(1);
            if (!isValidName(name)) {
                return unsupported(value, "unsupported variable form '" + value
                        + "' (only $NAME and ${NAME} are supported)");
            }
            return new VariableExpression(Form.NO_DEFAULT, value, name, null, -1, -1, null);
        }
        return unsupported(value, "variable reference concatenated with literal text");
    }

    private static int indexOfOperator(String body) {
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == ':' || c == '-' || c == '+' || c == '?' || c == '#' || c == '%' || c == '/') {
                return i;
            }
        }
        return -1;
    }

    private static boolean isValidName(String name) {
        if (name.isEmpty() || !(Character.isLetter(name.charAt(0)) || name.charAt(0) == '_')) {
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

    private static VariableExpression unsupported(String raw, String problem) {
        return new VariableExpression(Form.UNSUPPORTED, raw, null, null, -1, -1, problem);
    }

    public Form getForm() {
        return form;
    }

    public boolean isSupported() {
        return form != Form.UNSUPPORTED;
    }

    public String getRaw() {
        return raw;
    }

    /** @return the variable name for supported forms, otherwise {@code null}. */
    public String getName() {
        return name;
    }

    /** @return the inline default text, or {@code null}. */
    public String getInlineDefault() {
        return inlineDefault;
    }

    /** Offset of the inline default inside {@link #getRaw()}, or -1. */
    public int getInlineDefaultStart() {
        return inlineDefaultStart;
    }

    /** One past the inline default inside {@link #getRaw()}, or -1. */
    public int getInlineDefaultEnd() {
        return inlineDefaultEnd;
    }

    /** @return a human-readable reason for {@link Form#UNSUPPORTED}, otherwise {@code null}. */
    public String getProblem() {
        return problem;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof VariableExpression)) {
            return false;
        }
        VariableExpression that = (VariableExpression) other;
        return form == that.form && Objects.equals(raw, that.raw) && Objects.equals(name, that.name)
                && Objects.equals(inlineDefault, that.inlineDefault) && Objects.equals(problem, that.problem);
    }

    @Override
    public int hashCode() {
        return Objects.hash(form, raw, name, inlineDefault, problem);
    }

    @Override
    public String toString() {
        return "VariableExpression{" + raw + ", " + form + (name != null ? ", name=" + name : "")
                + (problem != null ? ", problem=" + problem : "") + "}";
    }
}
