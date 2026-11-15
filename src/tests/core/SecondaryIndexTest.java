package tests.core;

import main.core.*;
import main.core.domainExceptions.IndexAlreadyExistsException;
import main.core.domainExceptions.UnknownColumnName;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

class SecondaryIndexTest {
    @TempDir
    Path dbDir;

    private Table table;

    @BeforeEach
    void setUp() throws IOException {
        ColumnDef[] cols = new ColumnDef[]{
                new ColumnDef("id", ColumnType.INT, false, true),
                new ColumnDef("department", ColumnType.STRING, false, false),
                new ColumnDef("salary", ColumnType.INT, false, false)
        };
        TableSchema schema = new TableSchema("employees", cols);
        table = new Table(dbDir, schema);

        table.insert(new Object[]{1, "IT", 100000});
        table.insert(new Object[]{2, "HR", 60000});
        table.insert(new Object[]{3, "IT", 120000});
        table.insert(new Object[]{4, "Sales", 80000});
    }

    @Test
    void createIndex_andSelectViaIndexTest() throws IOException {
        // Создаем индекс по не-уникальному столбцу department
        table.createIndex("department");

        // Выборка должна автоматически пойти через secondaryIndex
        Map<String, Object[]> itEmployees = table.selectItems(
                new String[]{"id", "salary"},
                new String[]{"department"},
                row -> row.eq(0, "IT")
        );

        Assertions.assertAll(
                () -> Assertions.assertTrue(table.getSchema().getIndexedColumns().contains("department")),
                () -> Assertions.assertEquals(2, itEmployees.get("id").length),
                () -> Assertions.assertThrows(IndexAlreadyExistsException.class, () -> table.createIndex("department")),
                () -> Assertions.assertThrows(IndexAlreadyExistsException.class, () -> table.createIndex("id")), // PK уже проиндексирован
                () -> Assertions.assertThrows(UnknownColumnName.class, () -> table.createIndex("non_existent"))
        );
    }

    @Test
    void secondaryIndex_updatesOnInsertUpdateDeleteAndCompactTest() throws IOException {
        table.createIndex("department");

        // 1. Insert новой записи в IT
        table.insert(new Object[]{5, "IT", 95000});

        // 2. Update: переводим сотрудника 2 из HR в IT
        table.updateItems(
                Map.of("department", "IT"),
                new String[]{"id"},
                row -> row.eq(0, 2)
        );

        // 3. Delete: удаляем сотрудника 3 из IT
        table.deleteItems(
                new String[]{"id"},
                row -> row.eq(0, 3)
        );

        // 4. Compact: сжимаем файл (смещения меняются, но вторичный индекс хранит PK!)
        table.compact();

        // В IT должны остаться сотрудники: 1, 5 и 2
        Map<String, Object[]> itAfterChanges = table.selectItems(
                new String[]{"id"},
                new String[]{"department"},
                row -> row.eq(0, "IT")
        );

        // В HR никого не должно остаться
        Map<String, Object[]> hrAfterChanges = table.selectItems(
                new String[]{"id"},
                new String[]{"department"},
                row -> row.eq(0, "HR")
        );

        Assertions.assertAll(
                () -> Assertions.assertEquals(3, itAfterChanges.get("id").length),
                () -> Assertions.assertEquals(0, hrAfterChanges.get("id").length)
        );
    }

    @Test
    void secondaryIndex_persistsAcrossTableReopenTest() throws IOException {
        table.createIndex("salary");

        // Переоткрываем таблицу с диска
        Table reopened = new Table(dbDir, "employees");

        Map<String, Object[]> highEarners = reopened.selectItems(
                new String[]{"id", "department"},
                new String[]{"salary"},
                row -> row.gt(0, 90000)
        );

        Assertions.assertAll(
                () -> Assertions.assertTrue(reopened.getSchema().getIndexedColumns().contains("salary")),
                // Повторная попытка создать индекс должна упасть, так как он загрузился из JSON
                () -> Assertions.assertThrows(IndexAlreadyExistsException.class, () -> reopened.createIndex("salary")),
                () -> Assertions.assertEquals(2, highEarners.get("id").length)
        );
    }
}
