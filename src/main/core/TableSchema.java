package main.core;

//import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

import main.core.domainExceptions.ColumnNotFoundException;
import main.core.domainExceptions.DuplicateColumnNameException;
import main.core.domainExceptions.KeyNotUniqueException;
import main.core.domainExceptions.NullableKeyException;

public final class TableSchema{
    //Первый столбец всегда pk, поэтому не нужно хранить статус в колонке, нужна упорядоченная map
    //Хранение в hashmap нужно для быстрого поиска столбца по имени

    private final Map<String, ColumnDef> columnsByName = new LinkedHashMap<>();
    private final String tableName;
    public TableSchema(String tableName , ColumnDef[] cols) {
        this.tableName = tableName;
        if(cols[0].nullable()){
            throw new NullableKeyException("Первый столбец - PK, не может быть nullable");
        }
        if(!cols[0].isUniquie()){
            throw new KeyNotUniqueException("Первый столбец - PK, должен быть Unique");
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
}

