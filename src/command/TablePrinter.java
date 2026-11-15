package command;

import core.ColumnDef;
import core.TableSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class TablePrinter {

    private TablePrinter() {}

    public static String print(TableSchema schema, List<String> columns,
                               List<Map<String, Object>> rows) {
        List<String> headers = resolveHeaders(schema, columns, rows);
        List<List<String>> cells = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            List<String> line = new ArrayList<>(headers.size());
            for (String h : headers) line.add(format(row.get(h)));
            cells.add(line);
        }

        int[] width = new int[headers.size()];
        for (int i = 0; i < headers.size(); i++) width[i] = headers.get(i).length();
        for (List<String> line : cells) {
            for (int i = 0; i < line.size(); i++) {
                if (line.get(i).length() > width[i]) width[i] = line.get(i).length();
            }
        }

        StringBuilder sb = new StringBuilder();
        String sep = buildSep(width);
        sb.append(sep).append('\n');
        sb.append(buildRow(headers, width)).append('\n');
        sb.append(sep).append('\n');
        for (List<String> line : cells) sb.append(buildRow(line, width)).append('\n');
        sb.append(sep).append('\n');
        sb.append(rows.size()).append(" row(s)");
        return sb.toString();
    }

    private static List<String> resolveHeaders(TableSchema schema, List<String> columns,
                                               List<Map<String, Object>> rows) {
        if (columns != null && !columns.isEmpty()) return columns;
        if (!rows.isEmpty()) return new ArrayList<>(rows.get(0).keySet());
        List<String> out = new ArrayList<>();
        for (ColumnDef c : schema.getColumns()) out.add(c.name());
        return out;
    }

    private static String format(Object v) {
        return v == null ? "NULL" : v.toString();
    }

    private static String buildSep(int[] width) {
        StringBuilder sb = new StringBuilder("+");
        for (int w : width) sb.append("-".repeat(w + 2)).append('+');
        return sb.toString();
    }

    private static String buildRow(List<String> cells, int[] width) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < cells.size(); i++) {
            String c = cells.get(i);
            sb.append(' ').append(c);
            sb.append(" ".repeat(width[i] - c.length() + 1)).append('|');
        }
        return sb.toString();
    }
}