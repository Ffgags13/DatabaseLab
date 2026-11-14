package tests.core;
import main.core.ColumnDef;
import main.core.ColumnType;
import main.core.TableSchema;
import main.core.domainExceptions.DuplicateColumnNameException;
import main.core.domainExceptions.KeyNotUniqueException;
import main.core.domainExceptions.NullableKeyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
class TableSchemaTest {

    @Test
    void TableSchema_nullPK(){
        ColumnDef [] cols = new ColumnDef[]{ new ColumnDef("PK", ColumnType.INT, true, true)};
        Assertions.assertThrows(NullableKeyException.class, () ->
                        new TableSchema("Test", cols));
    }

    @Test
    void TableSchema_notuniquePK(){
        ColumnDef [] cols = new ColumnDef[]{ new ColumnDef("PK", ColumnType.INT, false)};
        Assertions.assertThrows(KeyNotUniqueException.class, () ->
                new TableSchema("Test", cols));
    }

    @Test
    void TableSchema_colAlreadyExists(){
        ColumnDef [] cols = new ColumnDef[]{
                new ColumnDef("PK", ColumnType.INT, false,true),
                new ColumnDef("PK", ColumnType.DOUBLE, true)
        };
        Assertions.assertThrows(DuplicateColumnNameException.class, () ->
                new TableSchema("Test", cols));
    }

    @Test
    void TableSchema_Check(){
        ColumnDef [] cols = new ColumnDef[]{
                new ColumnDef("PK", ColumnType.INT, false,true),
                new ColumnDef("2", ColumnType.DOUBLE, true, false)
        };
        TableSchema tab1 = new TableSchema("Test", cols);
        Assertions.assertArrayEquals(tab1.getColumns(), cols);
    }

    @Test
    void getColumn(){
        ColumnDef [] cols = new ColumnDef[]{
                new ColumnDef("PK", ColumnType.INT, false,true),
                new ColumnDef("2", ColumnType.DOUBLE, true, false)
        };
        TableSchema tab1 = new TableSchema("Test", cols);
        Assertions.assertEquals(tab1.getColumn("2"), cols[1]);
    }
    @Test
    void getPKColumn(){
        ColumnDef [] cols = new ColumnDef[]{
                new ColumnDef("PK", ColumnType.INT, false,true),
                new ColumnDef("2", ColumnType.DOUBLE, true, false)
        };
        TableSchema tab1 = new TableSchema("Test", cols);
        Assertions.assertEquals(tab1.pkColumn(), cols[0]);
    }

}
