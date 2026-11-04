package core;

//import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

import core.domainExceptions.ColumnNotFoundException;
import core.domainExceptions.DuplicateColumnNameException;
import core.domainExceptions.NullableKeyException;

public final class TableSchema{
    //Первый столбец всегда pk, поэтому не нужно хранить статус в колонке, нужна упорядоченная map
    //Хранение в hashmap нужно для быстрого поиска столбца по имени, без перебора
    //private final ArrayList<ColumnDef> columns = new ArrayList<>();
    private final Map<String, ColumnDef> columnsByName = new LinkedHashMap<>();
    private final String tableName;
    public TableSchema(String tableName , ColumnDef[] cols) {
        this.tableName = tableName;
        if (cols == null || cols.length == 0)
            throw new IllegalArgumentException("Схема не может быть пустой");
        if (cols[0] == null)
            throw new IllegalArgumentException("Первая колонка (PK) не задана");
        if(cols[0].nullable()){
            throw new NullableKeyException("Первый столбец не может быть nullable(всегда PK)");
        }
        for (ColumnDef col : cols) {
            if (columnsByName.containsKey(col.name())) {
                throw new DuplicateColumnNameException("Имя столбца " + col.name() + " используется дважды. ");
            }
            columnsByName.put(col.name(), col);
        }
    }
    public ColumnDef pkColumn() {
        return columnsByName.values().iterator().next();
    }

    public ColumnDef getColumn(String colName){
        if (!columnsByName.containsKey(colName)){
            throw new ColumnNotFoundException("Не найден столбец " + colName);
        }
        return(columnsByName.get(colName));
    }

    public String getTableName() {
        return tableName;
    }

    public ColumnDef[] getColumns() {
        return columnsByName.values().toArray(new ColumnDef[0]);

    }

    public boolean hasColumn(String colName) {

        return columnsByName.containsKey(colName);
    }

}

