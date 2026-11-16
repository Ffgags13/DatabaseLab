package main.ui;

import main.core.TableSchema;

import java.util.List;
import java.util.Map;

public record QueryResult(
        boolean success,
        String message,
        TableSchema schema,
        List<Object[]> rowsData,
        Map<String, Object[]> columnsData
) {
    public static QueryResult message(String msg) {
        return new QueryResult(true, msg, null, null, null);
    }

    public static QueryResult error(String errorMsg) {
        return new QueryResult(false, "Ошибка: " + errorMsg, null, null, null);
    }

    public static QueryResult tableRows(TableSchema schema, List<Object[]> rows) {
        return new QueryResult(true, null, schema, rows, null);
    }

    public static QueryResult tableColumns(Map<String, Object[]> columns) {
        return new QueryResult(true, null, null, null, columns);
    }
}
