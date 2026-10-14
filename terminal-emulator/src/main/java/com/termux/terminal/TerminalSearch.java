package com.termux.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Searches the whole transcript (scrollback history and the screen) of a {@link TerminalBuffer}.
 *
 * Rows that were wrapped because they were too long for the terminal width are searched as one
 * logical line, so a match can span several rows.
 */
public final class TerminalSearch {

    /** Maximum number of matches returned by a single search. */
    public static final int MAX_MATCHES = 5000;
    /** Maximum number of characters of the logical line kept as the match preview. */
    private static final int MAX_PREVIEW_LENGTH = 200;

    /** A single match of a search. */
    public static final class Match {
        /** The external row on which the match starts, from {@code -activeTranscriptRows} to {@code screenRows - 1}. */
        public final int row;
        /** The index of {@link #row} counted from the oldest line of the transcript, starting at 1. */
        public final int lineNumber;
        /** Flat array of (row, startColumn, endColumn exclusive) triplets, one per row covered by the match. */
        public final int[] segments;
        /** The text of the logical line containing the start of the match. */
        public final String preview;

        Match(int row, int lineNumber, int[] segments, String preview) {
            this.row = row;
            this.lineNumber = lineNumber;
            this.segments = segments;
            this.preview = preview;
        }
    }

    private TerminalSearch() {}

    /**
     * Compile a user query into a {@link Pattern}.
     *
     * @param query The text typed by the user.
     * @param regex Whether {@code query} is a regular expression, otherwise it is searched literally.
     * @param caseSensitive Whether the case must match.
     * @throws java.util.regex.PatternSyntaxException If {@code regex} is true and the query is invalid.
     */
    public static Pattern compile(String query, boolean regex, boolean caseSensitive) {
        int flags = caseSensitive ? 0 : (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        if (!regex) flags |= Pattern.LITERAL;
        return Pattern.compile(query, flags);
    }

    /** Find all matches of {@code pattern} in the transcript of {@code buffer}, oldest first. */
    public static List<Match> search(TerminalBuffer buffer, Pattern pattern) {
        List<Match> matches = new ArrayList<>();
        if (buffer == null || pattern == null) return matches;

        final int firstRow = -buffer.getActiveTranscriptRows();
        final int lastRow = buffer.mScreenRows - 1;

        StringBuilder text = new StringBuilder();
        // For every char of text: the row, and the first and last+1 column it occupies.
        int[] rowOf = new int[256];
        int[] colStartOf = new int[256];
        int[] colEndOf = new int[256];

        int row = firstRow;
        while (row <= lastRow) {
            text.setLength(0);
            final int lineFirstRow = row;
            int length = 0;

            while (true) {
                TerminalRow terminalRow = buffer.mLines[buffer.externalToInternalRow(row)];
                int column = 0;
                if (terminalRow != null) {
                    final char[] chars = terminalRow.mText;
                    final int used = terminalRow.getSpaceUsed();
                    int index = 0;
                    while (index < used) {
                        final char c = chars[index];
                        final boolean isHigh = Character.isHighSurrogate(c) && index + 1 < used;
                        final int codePoint = isHigh ? Character.toCodePoint(c, chars[index + 1]) : c;
                        final int charCount = isHigh ? 2 : 1;
                        final int width = WcWidth.width(codePoint);

                        final int start;
                        final int end;
                        if (width > 0) {
                            start = column;
                            end = column + width;
                            column = end;
                        } else {
                            // Combining or zero width character: attach to the previous column.
                            start = Math.max(0, column - 1);
                            end = Math.max(1, column);
                        }

                        if (length + charCount > rowOf.length) {
                            final int newSize = Math.max(rowOf.length * 2, length + charCount);
                            rowOf = java.util.Arrays.copyOf(rowOf, newSize);
                            colStartOf = java.util.Arrays.copyOf(colStartOf, newSize);
                            colEndOf = java.util.Arrays.copyOf(colEndOf, newSize);
                        }
                        for (int i = 0; i < charCount; i++) {
                            rowOf[length + i] = row;
                            colStartOf[length + i] = start;
                            colEndOf[length + i] = end;
                        }
                        text.append(chars, index, charCount);
                        length += charCount;
                        index += charCount;
                    }
                }

                final boolean wrapsToNextRow = terminalRow != null && terminalRow.mLineWrap && row < lastRow;
                row++;
                if (!wrapsToNextRow) break;
            }

            if (length == 0) continue;

            final Matcher matcher = pattern.matcher(text);
            String preview = null;
            while (matcher.find()) {
                final int start = matcher.start();
                final int end = matcher.end();
                if (end <= start) continue; // Ignore empty matches, like with the regex "a*".

                if (preview == null) preview = buildPreview(text);
                matches.add(new Match(rowOf[start], rowOf[start] - firstRow + 1,
                    buildSegments(start, end, rowOf, colStartOf, colEndOf), preview));
                if (matches.size() >= MAX_MATCHES) return matches;
            }
        }
        return matches;
    }

    private static String buildPreview(CharSequence text) {
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--;
        if (end > MAX_PREVIEW_LENGTH) end = MAX_PREVIEW_LENGTH;
        return text.subSequence(0, end).toString();
    }

    private static int[] buildSegments(int start, int end, int[] rowOf, int[] colStartOf, int[] colEndOf) {
        final List<Integer> out = new ArrayList<>();
        int segmentRow = rowOf[start];
        int segmentStart = colStartOf[start];
        int segmentEnd = colEndOf[start];
        for (int i = start + 1; i < end; i++) {
            if (rowOf[i] != segmentRow) {
                out.add(segmentRow);
                out.add(segmentStart);
                out.add(segmentEnd);
                segmentRow = rowOf[i];
                segmentStart = colStartOf[i];
                segmentEnd = colEndOf[i];
            } else {
                segmentStart = Math.min(segmentStart, colStartOf[i]);
                segmentEnd = Math.max(segmentEnd, colEndOf[i]);
            }
        }
        out.add(segmentRow);
        out.add(segmentStart);
        out.add(segmentEnd);

        final int[] result = new int[out.size()];
        for (int i = 0; i < result.length; i++) result[i] = out.get(i);
        return result;
    }

}
