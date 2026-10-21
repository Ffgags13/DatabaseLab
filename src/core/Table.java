package core;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class Table {
    private final Map<String, Column> schema = new LinkedHashMap<>();
    private final Map<Object, Row> rows = new LinkedHashMap<>();

    public Table(Map<String, ColumnType> columnTypes){
        if (columnTypes == null || columnTypes.isEmpty()) {
            throw new IllegalArgumentException("Схема не может быть пустой");
        }
        columnTypes.forEach((name, type) -> { this.schema.put(name, new Column(name, type, false, false));});
    }





    public synchronized void insert(Row row) {
//Получаем 1 ключ в row который является PK
        Object pkValue = row.getData().keySet().iterator().next();

        if (pkValue == null) {
            throw new IllegalArgumentException("Primary key не может быть null");
        }

        if (rows.containsKey(pkValue)) {
            throw new IllegalArgumentException("Дубликат первичного ключа: " + pkValue);
        }
        // Сохраняем по ключу
        rows.put(pkValue, row);
    }
    public boolean deleteById(Object pkValue) {
        return rows.remove(pkValue) != null;
    }

    // Геттер для вывода всех строк (например, для SELECT *)
    public Collection<Row> getRows() {
        return rows.values();
    }







}
