/**
 * Structured Dockerfile parsing and minimal-edit rewriting.
 *
 * <h2>Guarantees</h2>
 * <ul>
 *     <li>Parsing never throws on unusual content ({@link DockerfileParser#parseText} is total).
 *     Bytes that cannot be handled without transcoding are rejected up front with
 *     {@link UnsupportedDockerfileEncodingException}.</li>
 *     <li>The rewriter edits only the byte range holding a tag value. Line terminators, trailing
 *     whitespace, comment indentation, a missing final newline and a byte-order mark are copied
 *     through unchanged. When nothing is updated, the output equals the input exactly.</li>
 *     <li>Any reference that cannot be resolved statically is skipped and reported via
 *     {@link Diagnostic}; the rest of the file is still processed.</li>
 * </ul>
 *
 * <h2>Supported variable forms</h2>
 * {@code $NAME}, {@code ${NAME}} and {@code ${NAME:-default}}. Everything else is recognised but
 * marked unsupported (see {@link VariableExpression}).
 */
package com.salesforce.dockerfileimageupdate.parser;
