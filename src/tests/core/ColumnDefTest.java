package tests.core;
import main.core.ColumnDef;
import main.core.ColumnType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
class ColumnDefTest {

    @Test
    void ColumnDef(){
        ColumnDef Column1 = new ColumnDef("abc", ColumnType.DOUBLE, true);
        ColumnDef Column2 = new ColumnDef("abc", ColumnType.DOUBLE, true, false);
        Assertions.assertEquals(Column1, Column2);
    }
}
