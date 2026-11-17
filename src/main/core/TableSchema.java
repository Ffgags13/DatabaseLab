package main.core;

//import java.io.Serializable;
import java.util.*;

import main.core.domainExceptions.*;

public final class TableSchema{
    //Первый столбец всегда pk, поэтому не нужно хранить статус в колонке, нужна упорядоченная map
    //Хранение в hashmap нужно для быстрого поиска столбца по имени
    /**
     * Схема таблицы базы данных, содержащая описание столбцов, индексов и внешних ключей.
     * <p>
     * Первый столбец в переданном массиве всегда считается первичным ключом (PK),
     * поэтому он обязан быть неуникальным запрещено ({@code isUniquie == true}) и не допускать {@code null}.
     */
    private Set<String> indexedColumns = new HashSet<>();
    private final Map<String, ColumnDef> columnsByName = new LinkedHashMap<>();
    private final String tableName;
    private Map<String, Link> foreignKeys = new HashMap<>();

    /**
     * Создаёт схему таблицы с заданным именем и набором столбцов.
     *
     * @param tableName имя таблицы
     * @param cols      массив описаний столбцов (первый элемент становится первичным ключом)
     * @throws IllegalArgumentException     если массив столбцов пуст или равен {@code null}
     * @throws NullableKeyException         если первичный ключ (первый столбец) допускает {@code null}
     * @throws KeyNotUniqueException        если первичный ключ не помечен как уникальный
     * @throws DuplicateColumnNameException если в массиве встречаются столбцы с одинаковыми именами
     */
    public TableSchema(String tableName , ColumnDef[] cols) {
        if(cols == null || cols.length == 0){
            throw new IllegalArgumentException();
        }
        this.tableName = tableName;
        if(cols[0].nullable()){
            throw new NullableKeyException();
        }
        if(!cols[0].isUniquie()){
            throw new KeyNotUniqueException();
        }
        for (ColumnDef col : cols) {
            if (columnsByName.containsKey(col.name())) {
                throw new DuplicateColumnNameException(col.name());
            }
            columnsByName.put(col.name(), col);
        }
    }
    /**
     * Возвращает описание столбца первичного ключа (первого столбца схемы).
     *
     * @return описание столбца PK
     */
    public ColumnDef pkColumn() {
        return columnsByName.values().iterator().next();
    }

    /**
     * Возвращает описание столбца по его имени.
     *
     * @param colName имя искомого столбца
     * @return описание столбца {@link ColumnDef}
     * @throws UnknownColumnName если столбец с таким именем отсутствует в схеме
     */
    public ColumnDef getColumn(String colName){
        if (!columnsByName.containsKey(colName)){
            throw new UnknownColumnName(colName);
        }
        return(columnsByName.get(colName));
    }

    public String getTableName() {
        return tableName;
    }

    public ColumnDef[] getColumns() {
        return columnsByName.values().toArray(new ColumnDef[0]);
    }

    /**
     * Формирует подробное текстовое представление схемы в виде ASCII-таблицы
     * с указанием типов, допустимости {@code null} и ключей ({@code PRI}, {@code UNI}).
     *
     * @return отформатированная таблица со структурой схемы
     */
    public String showSchema() {
        ColumnDef[] cols = getColumns();
        ColumnDef pk = pkColumn();

        // 1. Вычисляем ширину столбцов с учетом заголовков и данных
        int fieldWidth = "Field".length();
        int typeWidth = "Type".length();
        int nullWidth = "Null".length();
        int keyWidth = "Key".length();

        for (ColumnDef col : cols) {
            fieldWidth = Math.max(fieldWidth, col.name().length());
            typeWidth = Math.max(typeWidth, col.type().name().length());
            // Null: "YES" или "NO" (максимум 3 символа)
            // Key: "PRI", "UNI" или "" (максимум 3 символа)
        }

        // 2. Собираем разделительную линию вида +-------+------+------+-----+
        String separator = "+" + "-".repeat(fieldWidth + 2)
                + "+" + "-".repeat(typeWidth + 2)
                + "+" + "-".repeat(nullWidth + 2)
                + "+" + "-".repeat(keyWidth + 2) + "+\n";

        // Шаблон строки с выравниванием по левому краю
        String rowFormat = "| %-" + fieldWidth + "s | %-" + typeWidth + "s | %-" + nullWidth + "s | %-" + keyWidth + "s |\n";

        StringBuilder sb = new StringBuilder();
        sb.append(separator);
        sb.append(String.format(rowFormat, "Field", "Type", "Null", "Key"));
        sb.append(separator);

        // 3. Заполняем строки таблицы
        for (ColumnDef col : cols) {
            String nullStr = col.nullable() ? "YES" : "NO";
            String keyStr = "";
            if (col.equals(pk)) {
                keyStr = "PRI";
            } else if (col.isUniquie()) {
                keyStr = "UNI";
            }

            sb.append(String.format(rowFormat, col.name(), col.type().name(), nullStr, keyStr));
        }

        sb.append(separator);
        return sb.toString();
    }
    /**
     * Формирует компактное однострочное представление схемы таблицы.
     *
     * @return краткое строковое описание столбцов и ограничений
     */
    public String showShortSchema() {
        ColumnDef[] cols = getColumns();
        ColumnDef pk = pkColumn();
        StringBuilder sb = new StringBuilder();

        sb.append("Table: ").append(tableName).append("\n");
        sb.append("| ");

        for (ColumnDef col : cols) {
            sb.append(col.name()).append(" (").append(col.type().name());

            if (col.equals(pk)) {
                sb.append(", PRI");
            } else if (col.isUniquie()) {
                sb.append(", UNI");
            }

            if (!col.nullable() && !col.equals(pk)) {
                sb.append(", NOT NULL");
            }

            sb.append(") | ");
        }

        return sb.toString().trim();
    }
    /**
     * Возвращает множество имён столбцов, для которых построены вторичные индексы.
     *
     * @return множество индексируемых столбцов
     */
    public Set<String> getIndexedColumns() {
        if (indexedColumns == null) {
            indexedColumns = new HashSet<>();
        }
        return indexedColumns;
    }
    /**
     * Регистрирует столбец в списке индексируемых.
     *
     * @param colName имя индексируемого столбца
     */
    public void addIndexedColumn(String colName) {
        getIndexedColumns().add(colName);
    }
    /**
     * Возвращает карту внешних ключей таблицы в формате {@code имя_столбца -> связь}.
     *
     * @return словарь внешних ключей
     */
    public Map<String, Link> getForeignKeys() {
        if (foreignKeys == null) {
            foreignKeys = new HashMap<>();
        }
        return foreignKeys;
    }

    /**
     * Устанавливает набор внешних ключей для таблицы.
     *
     * @param foreignKeys карта внешних ключей (если {@code null}, инициализируется пустой словарь)
     */
    public void setForeignKeys(Map<String, Link> foreignKeys) {
        this.foreignKeys = (foreignKeys != null) ? new HashMap<>(foreignKeys) : new HashMap<>();
    }
}
