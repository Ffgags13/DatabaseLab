package core;

import domainExceptions.ColumnNotFoundException;
import domainExceptions.DuplicateColumnNameException;
import domainExceptions.NullableKeyException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Схема таблицы: упорядоченный набор колонок. Первая колонка — первичный ключ.
 * <p>
 * Порядок колонок важен: по нему {@link RecordCodec} пишет поля в payload.
 */
public final class TableSchema {

    private final Map<String, ColumnDef> columnsByName = new LinkedHashMap<>();
    private final String tableName;

    /**
     * @param tableName имя таблицы
     * @param cols      колонки; первая — PK (не nullable)
     * @throws NullableKeyException       если первая колонка nullable
     * @throws DuplicateColumnNameException если имена колонок повторяются
     */
    public TableSchema(String tableName, ColumnDef[] cols) {
        if (tableName == null || tableName.isBlank())
            throw new IllegalArgumentException("Имя таблицы не может быть пустым");
        if (cols == null || cols.length == 0)
            throw new IllegalArgumentException("Схема не может быть пустой");
        if (cols[0] == null)
            throw new IllegalArgumentException("Первая колонка (PK) не задана");

        this.tableName = tableName;
        if (cols[0].nullable())
            throw new NullableKeyException("PK (первая колонка) не может быть nullable");

        for (ColumnDef col : cols) {
            if (columnsByName.containsKey(col.name()))
                throw new DuplicateColumnNameException(
                        "Имя столбца " + col.name() + " используется дважды");
            columnsByName.put(col.name(), col);
        }
    }

    /** PK — первая колонка. */
    public ColumnDef pkColumn() {
        return columnsByName.values().iterator().next();
    }

    /**
     * Найти колонку по имени.
     *
     * @throws ColumnNotFoundException если колонки нет
     */
    public ColumnDef getColumn(String colName) {
        ColumnDef c = columnsByName.get(colName);
        if (c == null)
            throw new ColumnNotFoundException("Не найден столбец " + colName);
        return c;
    }

    public boolean hasColumn(String colName) {
        return columnsByName.containsKey(colName);
    }

    public String getTableName() {
        return tableName;
    }

    /** Упорядоченный массив колонок (копия). */
    public ColumnDef[] getColumns() {
        return columnsByName.values().toArray(new ColumnDef[0]);
    }
}