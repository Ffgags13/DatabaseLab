package tests.core;

import main.core.*;
import main.core.domainExceptions.IncorrectTypeInConditionException;
import main.core.domainExceptions.NonUniqueValueException;
import main.core.domainExceptions.UnknownColumnName;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class TableTest {
    @TempDir
    Path dbDir;

    private Table table;

    @BeforeEach
    void setUp() throws IOException {
        ColumnDef[] cols = new ColumnDef[]{
                new ColumnDef("id", ColumnType.INT, false, true),
                new ColumnDef("username", ColumnType.STRING, false, true),
                new ColumnDef("age", ColumnType.INT, false, false),
                new ColumnDef("active", ColumnType.BOOLEAN, false, false)
        };
        TableSchema schema = new TableSchema("users", cols);
        table = new Table(dbDir, schema);
    }

    @Test
    void createAndReopenTable_persistsSchemaAndDataTest() throws IOException {
        table.insert(new Object[]{1, "alice", 22, true});
        table.insert(new Object[]{2, "bob", 30, false});

        Assertions.assertTrue(Files.exists(dbDir.resolve("users.dat")));
        Assertions.assertTrue(Files.exists(dbDir.resolve("users.schema.json")));

        Table reopened = new Table(dbDir, "users");
        List<Object[]> rows = reopened.getAllRows();

        Assertions.assertAll(
                () -> Assertions.assertEquals(2, rows.size()),
                () -> Assertions.assertEquals("alice", rows.get(0)[1]),
                () -> Assertions.assertEquals(30, rows.get(1)[2]),
                () -> Assertions.assertTrue(reopened.getShortSchema().contains("Table: users"))
        );
    }

    @Test
    void insert_validationAndUniquenessTest() throws IOException {
        table.insert(new Object[]{1, "alice", 22, true});

        Assertions.assertAll(
                () -> Assertions.assertThrows(IllegalArgumentException.class,
                        () -> table.insert(new Object[]{2, "bob"})),
                () -> Assertions.assertThrows(NonUniqueValueException.class,
                        () -> table.insert(new Object[]{2, "alice", 25, true}))
        );
    }

    @Test
    void selectItems_filteringAndExceptionsTest() throws IOException {
        table.insert(new Object[]{1, "alice", 22, true});
        table.insert(new Object[]{2, "bob", 17, true});
        table.insert(new Object[]{3, "charlie", 28, false});

        Map<String, Object[]> res = table.selectItems(
                new String[]{"id", "username"},
                new String[]{"age", "active"},
                row -> row.gt(0, 18) && row.eq(1, true)
        );

        Assertions.assertAll(
                () -> Assertions.assertArrayEquals(new Object[]{1}, res.get("id")),
                () -> Assertions.assertArrayEquals(new Object[]{"alice"}, res.get("username")),
                () -> Assertions.assertThrows(UnknownColumnName.class,
                        () -> table.selectItems(new String[]{"missing_col"}, null, null)),
                () -> Assertions.assertThrows(IncorrectTypeInConditionException.class,
                        () -> table.selectItems(new String[]{"id"}, new String[]{"age"}, row -> row.gt(0, "invalid_int")))
        );
    }

    @Test
    void updateItems_updatesMatchingRowsAndPreservesUniquenessTest() throws IOException {
        table.insert(new Object[]{1, "alice", 22, true});
        table.insert(new Object[]{2, "bob", 30, false});

        int updated = table.updateItems(
                Map.of("age", 23, "active", false),
                new String[]{"username"},
                row -> row.eq(0, "alice")
        );

        Map<String, Object[]> check = table.selectItems(
                new String[]{"age", "active"},
                new String[]{"id"},
                row -> row.eq(0, 1)
        );

        Assertions.assertAll(
                () -> Assertions.assertEquals(1, updated),
                () -> Assertions.assertArrayEquals(new Object[]{23}, check.get("age")),
                () -> Assertions.assertArrayEquals(new Object[]{false}, check.get("active"))
        );
    }

    @Test
    void deleteItems_andManualCompactTest() throws IOException {
        table.insert(new Object[]{1, "alice", 22, true});
        table.insert(new Object[]{2, "bob", 30, false});
        table.insert(new Object[]{3, "charlie", 19, false});

        int deleted = table.deleteItems(
                new String[]{"active"},
                row -> row.eq(0, false)
        );

        table.compact();
        List<Object[]> remaining = table.getAllRows();

        Assertions.assertAll(
                () -> Assertions.assertEquals(2, deleted),
                () -> Assertions.assertEquals(1, remaining.size()),
                () -> Assertions.assertEquals("alice", remaining.get(0)[1])
        );
    }
}
