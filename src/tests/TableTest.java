package tests;
import core.ColumnDef;
import core.ColumnType;
import core.Table;
import core.TableSchema;
import core.domainExceptions.DuplicateKeyException;
import core.domainExceptions.SchemaMismatchException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TableTest {

    private static TableSchema schema() {
        return new TableSchema(List.of(
                new ColumnDef("id",    ColumnType.INT,    true),
                new ColumnDef("brand", ColumnType.STRING, false),
                new ColumnDef("price", ColumnType.DOUBLE, false)
        ));
    }

    @Test
    void createInsertSelectReopen(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("cars.dat");
        try (Table t = Table.create(file, schema())) {
            t.insert(Map.of("id", 1, "brand", "Toyota", "price", 25000.0));
            t.insert(Map.of("id", 2, "brand", "BMW",    "price", 30000.0));

            List<Map<String, Object>> all = t.selectAll();
            assertEquals(2, all.size());
            assertEquals("Toyota", all.get(0).get("brand"));
            assertEquals("BMW",    all.get(1).get("brand"));
        }
        try (Table t = Table.open(file, schema())) {
            assertEquals(2, t.selectAll().size());
        }
    }

    @Test
    void duplicatePkRejected(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("cars.dat");
        try (Table t = Table.create(file, schema())) {
            t.insert(Map.of("id", 1, "brand", "Toyota"));
            assertThrows(DuplicateKeyException.class,
                    () -> t.insert(Map.of("id", 1, "brand", "Honda")));
        }
    }

    @Test
    void unknownColumnRejected(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("cars.dat");
        try (Table t = Table.create(file, schema())) {
            assertThrows(SchemaMismatchException.class,
                    () -> t.insert(Map.of("id", 1, "prcie", 100.0)));
        }
    }

    @Test
    void nullInRequiredRejected(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("cars.dat");
        try (Table t = Table.create(file, schema())) {
            assertThrows(SchemaMismatchException.class,
                    () -> t.insert(Map.of("brand", "Toyota"))); // id = null, PK
        }
    }

    @Test
    void emptyTableStats(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("cars.dat");
        try (Table t = Table.create(file, schema())) {
            var s = t.getStats();
            assertEquals(0, s.liveRecords());
            assertEquals(0, s.tombstoneRecords());
            assertEquals(0, s.fileSizeBytes());
        }
    }

    @Test
    void statsCountsLive(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("cars.dat");
        try (Table t = Table.create(file, schema())) {
            t.insert(Map.of("id", 1, "brand", "A"));
            t.insert(Map.of("id", 2, "brand", "B"));
            var s = t.getStats();
            assertEquals(2, s.liveRecords());
            assertEquals(0, s.tombstoneRecords());
            assertTrue(s.fileSizeBytes() > 0);
        }
    }

    @Test
    void createFailsIfFileExists(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("cars.dat");
        try (Table t = Table.create(file, schema())) {
            t.insert(Map.of("id", 1, "brand", "A"));
        }
        assertThrows(java.io.IOException.class,
                () -> Table.create(file, schema()));
    }

    @Test
    void openFailsIfNoFile(@TempDir Path dir) {
        assertThrows(java.io.IOException.class,
                () -> Table.open(dir.resolve("nope.dat"), schema()));
    }
}
