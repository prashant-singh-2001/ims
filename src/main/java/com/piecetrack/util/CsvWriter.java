package com.piecetrack.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Plain hand-written CSV (RFC 4180 quoting) for the "Export CSV" button every report
 *  screen has (docs/03-screens.md section 8) - no library needed for something this small. */
public final class CsvWriter {

    private CsvWriter() {
    }

    public static void write(Path file, List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        appendRow(sb, headers);
        for (List<String> row : rows) {
            appendRow(sb, row);
        }
        try {
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write CSV to " + file, e);
        }
    }

    private static void appendRow(StringBuilder sb, List<String> fields) {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(quote(fields.get(i)));
        }
        sb.append("\r\n");
    }

    private static String quote(String field) {
        if (field == null) {
            return "";
        }
        boolean needsQuoting = field.contains(",") || field.contains("\"") || field.contains("\n")
                || field.contains("\r");
        String escaped = field.replace("\"", "\"\"");
        return needsQuoting ? "\"" + escaped + "\"" : escaped;
    }
}
