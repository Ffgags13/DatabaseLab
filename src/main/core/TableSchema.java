package main.core;

//import java.io.Serializable;
import java.util.*;

import main.core.domainExceptions.*;

public final class TableSchema{
    //Первый столбец всегда pk, поэтому не нужно хранить статус в колонке, нужна упорядоченная map
    //Хранение в hashmap нужно для быстрого поиска столбца по имени
    private Set<String> indexedColumns = new HashSet<>();
    private final Map<String, ColumnDef> columnsByName = new LinkedHashMap<>();
    private final String tableName;
    private Map<String, Link> foreignKeys = new HashMap<>();

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
    public ColumnDef pkColumn() {
        return columnsByName.values().iterator().next();
    }

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
    public Set<String> getIndexedColumns() {
        if (indexedColumns == null) {
            indexedColumns = new HashSet<>();
        }
        return indexedColumns;
    }
    public void addIndexedColumn(String colName) {
        getIndexedColumns().add(colName);
    }
    public Map<String, Link> getForeignKeys() {
        if (foreignKeys == null) {
            foreignKeys = new HashMap<>();
        }
        return foreignKeys;
    }

    public void setForeignKeys(Map<String, Link> foreignKeys) {
        this.foreignKeys = (foreignKeys != null) ? new HashMap<>(foreignKeys) : new HashMap<>();
    }
}

