package main.ui;

import main.core.ColumnDef;
import main.core.TableSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Утилитный класс для форматированного табличного вывода данных в консоль.
 * <p>
 * Поддерживает отрисовку как полного набора строк таблицы, так и результатов
 * проекции по отдельным столбцам с автоматическим выравниванием ширины колонок.
 */
public final class TablePrinter {

    private TablePrinter() {
    }

    /**
     * Выводит в консоль все строки таблицы в виде ASCII-таблицы.
     *
     * @param schema - схема таблицы с описанием имён и порядка столбцов
     * @param rows - список строк таблицы (каждая строка представлена массивом значений)
     */
    public static void printRows(TableSchema schema, List<Object[]> rows) {
        if (schema == null) {
            System.out.println("(схема таблицы не задана)");
            return;
        }

        ColumnDef[] cols = schema.getColumns();
        List<String> headers = new ArrayList<>(cols.length);
        for (ColumnDef col : cols) {
            headers.add(col.name());
        }

        List<List<String>> tableData = new ArrayList<>();
        if (rows != null) {
            for (Object[] row : rows) {
                List<String> line = new ArrayList<>(cols.length);
                for (int i = 0; i < cols.length; i++) {
                    Object val = (row != null && i < row.length) ? row[i] : null;
                    line.add(val == null ? "NULL" : val.toString());
                }
                tableData.add(line);
            }
        }

        printGrid(headers, tableData);
    }

    /**
     * Выводит в консоль результат выборки по конкретным столбцам,
     * транспонируя поколоночное представление в построчную таблицу.
     *
     * @param data - словарь значений в формате {@code имя_столбца -> массив_значений}
     */
    public static void printColumns(Map<String, Object[]> data) {
        if (data == null || data.isEmpty()) {
            System.out.println("(нет данных для отображения)");
            return;
        }

        List<String> headers = new ArrayList<>(data.keySet());
        Object[] firstColValues = data.get(headers.get(0));
        int rowCount = (firstColValues == null) ? 0 : firstColValues.length;

        List<List<String>> tableData = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            List<String> line = new ArrayList<>(headers.size());
            for (String header : headers) {
                Object[] colValues = data.get(header);
                Object val = (colValues != null && i < colValues.length) ? colValues[i] : null;
                line.add(val == null ? "NULL" : val.toString());
            }
            tableData.add(line);
        }

        printGrid(headers, tableData);
    }

    /**
     * Вычисляет ширину столбцов и отрисовывает сетку таблицы с заголовками и строками.
     *
     * @param headers - список заголовков столбцов
     * @param rows - список отформатированных строк таблицы
     */
    private static void printGrid(List<String> headers, List<List<String>> rows) {
        int colCount = headers.size();
        int[] widths = new int[colCount];

        for (int i = 0; i < colCount; i++) {
            widths[i] = headers.get(i).length();
        }
        for (List<String> row : rows) {
            for (int i = 0; i < colCount; i++) {
                widths[i] = Math.max(widths[i], row.get(i).length());
            }
        }

        StringBuilder sepBuilder = new StringBuilder("+");
        for (int w : widths) {
            sepBuilder.append("-".repeat(w + 2)).append("+");
        }
        String separator = sepBuilder.toString();

        System.out.println(separator);
        printLine(headers, widths);
        System.out.println(separator);

        if (rows.isEmpty()) {
            System.out.println("(таблица пуста)");
        } else {
            for (List<String> row : rows) {
                printLine(row, widths);
            }
            System.out.println(separator);
        }
        System.out.println("Всего записей: " + rows.size());
    }

    /**
     * Форматирует и выводит одну строку таблицы с учётом заданной ширины каждого столбца.
     *
     * @param cells - список строковых значений ячеек строки
     * @param widths - массив максимальных ширин столбцов
     */
    private static void printLine(List<String> cells, int[] widths) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < cells.size(); i++) {
            String cell = cells.get(i);
            sb.append(" ").append(cell).append(" ".repeat(widths[i] - cell.length() + 1)).append("|");
        }
        System.out.println(sb);
    }
}
