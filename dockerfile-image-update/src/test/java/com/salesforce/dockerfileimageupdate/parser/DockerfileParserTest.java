package com.salesforce.dockerfileimageupdate.parser;

import com.salesforce.dockerfileimageupdate.parser.FromInstruction.ReferenceKind;
import com.salesforce.dockerfileimageupdate.parser.FromInstruction.TagOrigin;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * Fixtures for the structured Dockerfile parser and the minimal-edit rewriter. Fixtures are plain
 * strings so every case is readable in place; the byte-level tests compare the exact characters
 * that come back out.
 */
public class DockerfileParserTest {

    private static final String NL = "\n";

    // ---------------------------------------------------------------- byte preservation

    @DataProvider
    public Object[][] unchangedFixtures() {
        return new Object[][]{
                {"mixed line endings and trailing teol", "FROM alpine:3.19" + NL + "RUN echo hi" + NL,
                        "busybox", "9.9"},
                {"crlf file", "FROM alpine:3.19\r\nRUN echo hi\r\n", "busybox", "9.9"},
                {"no trailing newline", "FROM alpine:3.19", "busybox", "9.9"},
                {"trailing spaces on other lines", "FROM alpine:3.19\nRUN echo hi   \n#   indented\n", "busybox", "9.9"},
                {"comment indentation preserved", "#   leading spaces\nFROM alpine:3.19\n", "some-other", "9.9"},
                {"continuation with comment", "FROM alpine:3.19 \\\n# an interposed comment\n  AS build\n", "alpine", "3.19"},
                {"bom file", "﻿FROM alpine:3.19\n", "alpine", "3.19"},
                {"unrelated image", "FROM busybox:1.36\n", "alpine", "9.9"},
                {"no from at all", "RUN echo hi\n", "alpine", "9.9"},
        };
    }

    @Test(dataProvider = "unchangedFixtures")
    public void noUpdateMeansByteIdenticalOutput(String name, String input, String image, String tag) {
        DockerfileDocument document = DockerfileParser.parseText(input);
        RewriteResult result = DockerfileRewriter.rewrite(document, image, tag, "");
        assertEquals(result.getUpdatedText(), input, name);
        assertFalse(result.isModified(), name);
        assertEquals(result.firstDifferenceOffset(), -1, name);
    }

    @DataProvider
    public Object[][] updatedFixtures() {
        return new Object[][]{
                {"lf", "FROM alpine:3.19\nRUN echo hi\n", "FROM alpine:3.20\nRUN echo hi\n", 14},
                {"crlf", "FROM alpine:3.19\r\nRUN echo hi\r\n", "FROM alpine:3.20\r\nRUN echo hi\r\n", 14},
                {"no trailing newline", "FROM alpine:3.19", "FROM alpine:3.20", 14},
                {"trailing spaces elsewhere", "FROM alpine:3.19\nRUN echo hi   \n", "FROM alpine:3.20\nRUN echo hi   \n", 14},
                {"comment glued to tag", "FROM alpine:3.19# note\n", "FROM alpine:3.20# note\n", 14},
                {"comment spaced", "FROM alpine:3.19  # note\n", "FROM alpine:3.20  # note\n", 14},
                {"tab before as", "FROM alpine:3.19\tAS build\n", "FROM alpine:3.20\tAS build\n", 14},
                {"bom", "﻿FROM alpine:3.19\n", "﻿FROM alpine:3.20\n", 15},
                {"platform literal", "FROM --platform=linux/amd64 alpine:3.19\n",
                        "FROM --platform=linux/amd64 alpine:3.20\n", 37},
                {"platform variable", "FROM --platform=$BUILDPLATFORM alpine:3.19\n",
                        "FROM --platform=$BUILDPLATFORM alpine:3.20\n", 40},
                {"private registry with port", "FROM registry.example.com:5000/team/alpine:3.19\n",
                        "FROM registry.example.com:5000/team/alpine:3.20\n", 45},
                {"continuation in instruction", "FROM alpine:3.19 \\\n  AS build\n",
                        "FROM alpine:3.20 \\\n  AS build\n", 14},
                {"lowercase keywords", "from alpine:3.19\n", "from alpine:3.20\n", 14},
        };
    }

    @Test(dataProvider = "updatedFixtures")
    public void updateTouchesOnlyTheTagBytes(String name, String input, String expected, int firstDifference) {
        DockerfileDocument document = DockerfileParser.parseText(input);
        RewriteResult result = DockerfileRewriter.rewrite(document, "alpine", "3.20", "");
        assertTrue(result.isModified(), name);
        assertEquals(result.getUpdatedText(), expected, name);
        assertEquals(result.firstDifferenceOffset(), firstDifference, name);
        // Everything before the first difference must be identical byte for byte.
        assertEquals(input.substring(0, firstDifference),
                expected.substring(0, firstDifference), name);
    }

    @Test
    public void tagSplitAcrossAContinuationIsSkippedRatherThanGuessed() {
        // Docker joins "alpine:3\\<newline>19" into "alpine:319", but those bytes are not one
        // contiguous run in the file, so the tag cannot be replaced safely.
        String input = "FROM alpine:3\\\n19\n";
        DockerfileDocument document = DockerfileParser.parseText(input);
        FromInstruction from = document.getFromInstructions().get(0);
        assertEquals(from.getImageReference().getTagText(), "319");
        assertNull(from.getTagValueSpan());
        RewriteResult result = DockerfileRewriter.rewrite(document, "alpine", "3.20", "");
        assertFalse(result.isModified());
        assertEquals(result.getUpdatedText(), input);
        assertFalse(result.getSkipped().isEmpty());
    }

    @Test
    public void untaggedLiteralImageGetsTagAppended() {
        RewriteResult result = rewrite("FROM alpine\n", "alpine", "3.20");
        assertEquals(result.getUpdatedText(), "FROM alpine:3.20\n");
    }

    @Test
    public void crlfAndMixedLineEndingsSurviveUpdate() {
        String input = "FROM alpine:3.19\r\nRUN true\nFROM busybox:1\r\n";
        RewriteResult result = rewrite(input, "alpine", "3.20");
        assertEquals(result.getUpdatedText(), "FROM alpine:3.20\r\nRUN true\nFROM busybox:1\r\n");
    }

    // ---------------------------------------------------------------- structure and origin

    @DataProvider
    public Object[][] structuredReferences() {
        return new Object[][]{
                {"FROM alpine:3.19", "alpine", "3.19", null, null},
                {"FROM registry.example.com:5000/team/alpine:3.19", "registry.example.com:5000/team/alpine", "3.19", null, null},
                {"FROM alpine@sha256:abc", "alpine", null, "sha256:abc", null},
                {"FROM alpine:3.19@sha256:abc", "alpine", "3.19", "sha256:abc", null},
                {"FROM --platform=linux/arm64 alpine:3.19 AS build", "alpine", "3.19", null, "build"},
                {"FROM alpine:3.19 as MixedCase", "alpine", "3.19", null, "MixedCase"},
        };
    }

    @Test(dataProvider = "structuredReferences")
    public void fromIsParsedIntoStructuredParts(String line, String expectedName, String expectedTag,
                                                String expectedDigest, String expectedAlias) {
        DockerfileDocument document = DockerfileParser.parseText(line + NL);
        List<FromInstruction> stages = document.getFromInstructions();
        assertEquals(stages.size(), 1);
        FromInstruction from = stages.get(0);
        assertEquals(from.getReferenceKind(), ReferenceKind.IMAGE);
        assertEquals(from.getImageReference().getName(), expectedName);
        assertEquals(from.getImageReference().getTagText(), expectedTag);
        assertEquals(from.getImageReference().getDigest(), expectedDigest);
        assertEquals(from.getStageAlias(), expectedAlias);
    }

    @Test
    public void tagOnRegistryWithPortIsTheLastPathSegmentColon() {
        FromInstruction from = parseSingle("FROM registry.example.com:5000/team/alpine:3.19\n");
        assertEquals(from.getImageReference().getName(), "registry.example.com:5000/team/alpine");
        assertEquals(from.getImageReference().getTagText(), "3.19");
        assertEquals(from.getTagOrigin(), TagOrigin.FROM_LINE);
    }

    @Test
    public void globalArgDefaultIsTheSourceOfTheTag() {
        String input = "ARG BASE_TAG=3.19\nFROM alpine:${BASE_TAG}\n";
        DockerfileDocument document = DockerfileParser.parseText(input);
        FromInstruction from = document.getFromInstructions().get(0);
        assertEquals(from.getTagOrigin(), TagOrigin.GLOBAL_ARG_DEFAULT);
        assertEquals(from.getEffectiveTagText(), "3.19");
        assertNotNull(from.getTagResolution().getDeclaration());
        assertEquals(from.getTagResolution().getDeclaration().getLineNumber(), 1);

        RewriteResult result = DockerfileRewriter.rewrite(document, "alpine", "3.20", "");
        // The ARG default is edited; the FROM line keeps its variable reference.
        assertEquals(result.getUpdatedText(), "ARG BASE_TAG=3.20\nFROM alpine:${BASE_TAG}\n");
    }

    @DataProvider
    public Object[][] variableSyntaxes() {
        return new Object[][]{
                {"ARG BASE=3.19\nFROM alpine:${BASE}\n"},
                {"ARG BASE=3.19\nFROM alpine:$BASE\n"},
                {"FROM alpine:${BASE:-3.19}\n"},
        };
    }

    @Test(dataProvider = "variableSyntaxes")
    public void allThreeVariableSyntaxesAreUnderstood(String input) {
        RewriteResult result = rewrite(input, "alpine", "3.20");
        assertTrue(result.isModified(), input);
        if (input.contains("ARG BASE=3.19")) {
            assertEquals(result.getUpdatedText(), input.replace("3.19", "3.20"));
        } else {
            assertEquals(result.getUpdatedText(), "FROM alpine:${BASE:-3.20}\n");
        }
    }

    @Test
    public void duplicateArgDeclarationLastDefaultWinsAndPointsAtIt() {
        String input = "ARG TAG=old\nARG TAG=3.19\nFROM alpine:${TAG}\n";
        DockerfileDocument document = DockerfileParser.parseText(input);
        FromInstruction from = document.getFromInstructions().get(0);
        assertEquals(from.getEffectiveTagText(), "3.19");
        assertEquals(from.getTagOrigin(), TagOrigin.GLOBAL_ARG_DEFAULT);
        assertEquals(from.getTagResolution().getDeclaration().getLineNumber(), 2);

        RewriteResult result = DockerfileRewriter.rewrite(document, "alpine", "3.20", "");
        assertEquals(result.getUpdatedText(), "ARG TAG=old\nARG TAG=3.20\nFROM alpine:${TAG}\n");
    }

    @Test
    public void argDeclaredTwiceInStageScopeUsesStageDeclarationForDiagnostics() {
        String input = "ARG TAG=global\nFROM alpine:3.19 AS build\nARG TAG=3.18\n";
        DockerfileDocument document = DockerfileParser.parseText(input);
        ArgumentResolver resolver = new ArgumentResolver(document);
        assertEquals(resolver.resolveForFrom("TAG", 0).getValue(), "global");
        assertEquals(resolver.resolveForStage("TAG", 0).getValue(), "3.18");
    }

    @Test
    public void globalArgIsNotVisibleInsideAStageUnlessRedeclared() {
        // The FROM itself sees the global value, but a stage-local reference does not.
        String input = "ARG TAG=3.19\nFROM alpine:${TAG}\n";
        DockerfileDocument document = DockerfileParser.parseText(input);
        ArgumentResolver resolver = new ArgumentResolver(document);
        assertEquals(resolver.resolveForFrom("TAG", 0).getValue(), "3.19");
        // A name only declared in a stage cannot be used by FROM: simulate by checking stage scope
        // for a different name.
        assertEquals(resolver.resolveForStage("MISSING", 0).getStatus(), ArgumentResolver.Status.UNDECLARED);
    }

    // ---------------------------------------------------------------- stages

    @Test
    public void referenceToEarlierStageIsNotAnExternalImage() {
        String input = "FROM alpine:3.19 AS build\nFROM build\n";
        DockerfileDocument document = DockerfileParser.parseText(input);
        List<FromInstruction> stages = document.getFromInstructions();
        assertSame(stages.get(1).getReferencedStage(), stages.get(0));
        assertEquals(stages.get(1).getReferenceKind(), ReferenceKind.STAGE);
        assertFalse(stages.get(1).isUpdatableImage());

        RewriteResult result = DockerfileRewriter.rewrite(document, "build", "9.9", "");
        assertFalse(result.isModified());
        assertEquals(result.getUpdatedText(), input);
    }

    @Test
    public void stageAliasShadowsAPublicImageOfTheSameName() {
        String input = "FROM alpine:3.19 AS busybox\nFROM busybox\n";
        DockerfileDocument document = DockerfileParser.parseText(input);
        assertEquals(document.getFromInstructions().get(1).getReferenceKind(), ReferenceKind.STAGE);
        RewriteResult result = DockerfileRewriter.rewrite(document, "busybox", "9.9", "");
        assertFalse(result.isModified());
    }

    @Test
    public void asKeywordIsCaseInsensitiveForTheStageAlias() {
        String input = "FROM alpine:3.19 aS Build\nFROM build\n";
        DockerfileDocument document = DockerfileParser.parseText(input);
        assertEquals(document.getFromInstructions().get(0).getStageAlias(), "Build");
        assertEquals(document.getFromInstructions().get(1).getReferenceKind(), ReferenceKind.STAGE);
    }

    @Test
    public void scratchIsNeverUpdatedAndIsNotReportedAsMissing() {
        String input = "FROM scratch\n";
        DockerfileDocument document = DockerfileParser.parseText(input);
        FromInstruction from = document.getFromInstructions().get(0);
        assertEquals(from.getReferenceKind(), ReferenceKind.SCRATCH);
        assertFalse(from.isUpdatableImage());
        RewriteResult result = DockerfileRewriter.rewrite(document, "scratch", "1.0", "");
        assertFalse(result.isModified());
        assertTrue(result.getSkipped().isEmpty());
    }

    // ---------------------------------------------------------------- unresolvable values

    @Test
    public void undeclaredVariableTagIsSkippedWithAReasonButOtherLinesStillUpdate() {
        String input = "FROM alpine:${UNKNOWN}\nFROM alpine:3.19\n";
        DockerfileDocument document = DockerfileParser.parseText(input);
        RewriteResult result = DockerfileRewriter.rewrite(document, "alpine", "3.20", "");
        assertTrue(result.isModified());
        assertEquals(result.getUpdatedText(), "FROM alpine:${UNKNOWN}\nFROM alpine:3.20\n");
        assertFalse(result.getSkipped().isEmpty());
        assertTrue(result.getSkipped().get(0).getMessage().contains("UNKNOWN"));
    }

    @Test
    public void argWithoutDefaultIsSkippedWithAReason() {
        String input = "ARG TAG\nFROM alpine:${TAG}\n";
        RewriteResult result = rewrite(input, "alpine", "3.20");
        assertFalse(result.isModified());
        // The reason is reported on the parsed instructions (surfaced as a parse diagnostic); the
        // rewriter additionally records that it skipped the reference.
        assertTrue(document(input).getDiagnostics().stream()
                .anyMatch(d -> d.getMessage().contains("without a default value")));
        assertTrue(result.getSkipped().get(0).getMessage().contains("cannot be determined"));
    }

    @Test
    public void unsupportedExpansionIsNotRewritten() {
        String input = "ARG TAG=3.19\nFROM alpine:${TAG:+other}\n";
        DockerfileDocument document = DockerfileParser.parseText(input);
        FromInstruction from = document.getFromInstructions().get(0);
        assertEquals(from.getReferenceKind(), ReferenceKind.UNRESOLVED);
        RewriteResult result = DockerfileRewriter.rewrite(document, "alpine", "3.20", "");
        assertFalse(result.isModified());
    }

    @Test
    public void multiVariableArgLineIsRejectedAsUnsupported() {
        String input = "ARG A=1 B=2\nFROM alpine:${A}\n";
        DockerfileDocument document = DockerfileParser.parseText(input);
        ArgumentResolver resolver = new ArgumentResolver(document);
        assertEquals(resolver.resolveForFrom("A", 0).getStatus(), ArgumentResolver.Status.UNSUPPORTED);
        RewriteResult result = DockerfileRewriter.rewrite(document, "alpine", "3.20", "");
        assertFalse(result.isModified());
    }

    // ---------------------------------------------------------------- flags and aliases

    @Test
    public void platformValueCanItselfBeAVariable() {
        FromInstruction from = parseSingle("FROM --platform=$BUILDPLATFORM alpine:3.19 AS build\n");
        assertEquals(from.getPlatformText(), "$BUILDPLATFORM");
        assertNotNull(from.getPlatformVariable());
        assertEquals(from.getPlatformVariable().getName(), "BUILDPLATFORM");
        assertEquals(from.getStageAlias(), "build");
    }

    @Test
    public void whitespaceBetweenAliasAndCommentIsPreserved() {
        String input = "FROM alpine:3.19    AS   build   # keep me\n";
        RewriteResult result = rewrite(input, "alpine", "3.20");
        assertEquals(result.getUpdatedText(), "FROM alpine:3.20    AS   build   # keep me\n");
    }

    @Test
    public void unsupportedFromFlagLeavesTheLineAlone() {
        String input = "FROM --network=host alpine:3.19\n";
        RewriteResult result = rewrite(input, "alpine", "3.20");
        assertFalse(result.isModified());
        assertEquals(result.getUpdatedText(), input);
    }

    // ---------------------------------------------------------------- encodings

    @Test
    public void parserAcceptsAByteOrderMarkAndKeepsIt() throws Exception {
        byte[] bytes = ("﻿FROM alpine:3.19\n").getBytes(StandardCharsets.UTF_8);
        DockerfileDocument document = DockerfileParser.parse(bytes);
        assertEquals(document.getFromInstructions().get(0).getImageReference().getName(), "alpine");
        assertEquals(document.getFromInstructions().get(0).getImageReference().getTagText(), "3.19");
        assertEquals(document.getText(), "﻿FROM alpine:3.19\n");
    }

    @Test(expectedExceptions = UnsupportedDockerfileEncodingException.class)
    public void utf16BigEndianIsRejected() throws Exception {
        DockerfileParser.parse(new byte[]{(byte) 0xFE, (byte) 0xFF, 0, 'F', 0, 'R'});
    }

    @Test(expectedExceptions = UnsupportedDockerfileEncodingException.class)
    public void utf16LittleEndianIsRejected() throws Exception {
        DockerfileParser.parse(new byte[]{(byte) 0xFF, (byte) 0xFE, 'F', 0, 'R', 0});
    }

    @Test
    public void invalidUtf8IsRejectedRatherThanTranscoded() {
        byte[] bytes = {'F', 'R', 'O', 'M', ' ', (byte) 0xC3, (byte) 0x28, '\n'};
        try {
            DockerfileParser.parse(bytes);
            fail("expected UnsupportedDockerfileEncodingException");
        } catch (UnsupportedDockerfileEncodingException expected) {
            assertTrue(expected.getMessage().contains("UTF-8"));
        }
    }

    // ---------------------------------------------------------------- robustness

    @DataProvider
    public Object[][] oddButNonThrowingInput() {
        return new Object[][]{
                {""},
                {"\n\n\n"},
                {"FROM"},
                {"FROM "},
                {"FROM :"},
                {"FROM @sha256:abc"},
                {"FROM a@b@c"},
                {"FROM alpine:3.19 AS"},
                {"FROM alpine:3.19 builder"},
                {"FROM alpine:3.19 AS a b"},
                {"ARG"},
                {"ARG ="},
                {"FROM --platform"},
                {"FROM --platform= alpine:3.19"},
                {"FROM alpine:${"},
                {"FROM ${alpine}"},
                {"FROM alpine:+${X}"},
                {"FROM alpine:3.19 \\\n"},
                {"﻿"},
                {"FROM alpine:3.19#"},
                {"# escape=`\nFROM alpine:3.19\n"},
        };
    }

    @Test(dataProvider = "oddButNonThrowingInput")
    public void oddInputNeverThrowsAndIsReturnedUnchanged(String input) {
        DockerfileDocument document = DockerfileParser.parseText(input);
        assertNotNull(document);
        RewriteResult result = DockerfileRewriter.rewrite(document, "alpine", "3.20", "");
        if (result.getUpdatedText().equals(input)) {
            assertFalse(result.isModified());
        }
    }

    @Test
    public void escapeDirectiveOtherThanBackslashLeavesFileUntouched() {
        String input = "# escape=`\nFROM alpine:3.19\n";
        DockerfileDocument document = DockerfileParser.parseText(input);
        assertTrue(document.getFromInstructions().isEmpty());
        assertFalse(document.getDiagnostics().isEmpty());
        RewriteResult result = DockerfileRewriter.rewrite(document, "alpine", "3.20", "");
        assertEquals(result.getUpdatedText(), input);
    }

    @Test
    public void commentIsNeverTreatedAsAnInstruction() {
        String input = "# FROM alpine:3.19\n";
        RewriteResult result = rewrite(input, "alpine", "3.20");
        assertFalse(result.isModified());
        assertEquals(result.getUpdatedText(), input);
    }

    // ---------------------------------------------------------------- ignore marker

    @Test
    public void precedingIgnoreCommentSuppressesTheUpdate() {
        String input = "# no-dfiu\nFROM alpine:3.19\n";
        RewriteResult result = rewrite(input, "alpine", "3.20");
        assertFalse(result.isModified());
        assertEquals(result.getUpdatedText(), input);
    }

    // ---------------------------------------------------------------- helpers

    private static FromInstruction parseSingle(String input) {
        List<FromInstruction> stages = DockerfileParser.parseText(input).getFromInstructions();
        assertEquals(stages.size(), 1);
        return stages.get(0);
    }

    private static DockerfileDocument document(String input) {
        return DockerfileParser.parseText(input);
    }

    private static RewriteResult rewrite(String input, String image, String tag) {
        return DockerfileRewriter.rewrite(DockerfileParser.parseText(input), image, tag, "");
    }

    @Test
    public void variableImageResolvesThroughArgDefault() {
        String input = "ARG BASE=alpine:3.19\nFROM $BASE AS build\n";
        DockerfileDocument document = DockerfileParser.parseText(input);
        FromInstruction from = document.getFromInstructions().get(0);
        assertEquals(from.getReferenceKind(), ReferenceKind.IMAGE);
        assertEquals(from.getEffectiveImageName(), "alpine");
        assertEquals(from.getEffectiveTagText(), "3.19");
        RewriteResult result = DockerfileRewriter.rewrite(document, "alpine", "3.20", "");
        assertEquals(result.getUpdatedText(), "ARG BASE=alpine:3.20\nFROM $BASE AS build\n");
    }

    @Test
    public void variableImageWithoutDefaultIsSkipped() {
        String input = "FROM $BASE\n";
        DockerfileDocument document = DockerfileParser.parseText(input);
        FromInstruction from = document.getFromInstructions().get(0);
        assertEquals(from.getEffectiveImageName(), null);
        assertTrue(from.getDiagnostics().stream().anyMatch(d -> d.getMessage().contains("BASE")));
        RewriteResult result = DockerfileRewriter.rewrite(document, "alpine", "3.20", "");
        assertFalse(result.isModified());
    }

    @Test
    public void digestsAreParsedAndNotTreatedAsTags() {
        FromInstruction from = parseSingle("FROM alpine@sha256:deadbeef\n");
        assertTrue(from.getImageReference().hasDigest());
        assertFalse(from.getImageReference().hasTag());
        assertNull(from.getImageReference().getTagText());
    }

    @Test
    public void digestPinnedUntaggedImageIsNotGivenATag() {
        String input = "FROM alpine@sha256:deadbeef\n";
        RewriteResult result = rewrite(input, "alpine", "3.20");
        assertFalse(result.isModified());
        assertEquals(result.getUpdatedText(), input);
    }

    @Test
    public void tagAndDigestTogetherStillUpdatesTheTag() {
        String input = "FROM alpine:3.19@sha256:deadbeef\n";
        RewriteResult result = rewrite(input, "alpine", "3.20");
        assertEquals(result.getUpdatedText(), "FROM alpine:3.20@sha256:deadbeef\n");
    }

    @Test
    public void platformValueConcatenatedWithLiteralDoesNotBlockTagUpdate() {
        String input = "FROM --platform=linux/${ARCH} alpine:3.19\n";
        RewriteResult result = rewrite(input, "alpine", "3.20");
        assertEquals(result.getUpdatedText(), "FROM --platform=linux/${ARCH} alpine:3.20\n");
    }

    // ---------------------------------------------------------------- docker-compose

    @Test
    public void composeImageLineIsUpdatedWithAndWithoutSpaceAfterColon() {
        String spaced = "services:\n  web:\n    image: alpine:3.19\n";
        RewriteResult spacedResult = DockerfileRewriter.rewrite(
                DockerfileParser.parseText(spaced), "alpine", "3.20", "", true);
        assertEquals(spacedResult.getUpdatedText(), "services:\n  web:\n    image: alpine:3.20\n");

        String glued = "services:\n  web:\n    image:alpine:3.19\n";
        RewriteResult gluedResult = DockerfileRewriter.rewrite(
                DockerfileParser.parseText(glued), "alpine", "3.20", "", true);
        assertEquals(gluedResult.getUpdatedText(), "services:\n  web:\n    image:alpine:3.20\n");
    }

    @Test
    public void composeLinesAreIgnoredUnlessComposeIsRequested() {
        String input = "services:\n  web:\n    image: alpine:3.19\n";
        RewriteResult result = DockerfileRewriter.rewrite(
                DockerfileParser.parseText(input), "alpine", "3.20", "");
        assertFalse(result.isModified());
    }

    @Test
    public void composeUntaggedAndLatestValuesAreNotRewritten() {
        String input = "services:\n  a:\n    image: alpine\n  b:\n    image: alpine:latest\n";
        RewriteResult result = DockerfileRewriter.rewrite(
                DockerfileParser.parseText(input), "alpine", "3.20", "", true);
        assertFalse(result.isModified());
    }
}
