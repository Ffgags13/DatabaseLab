import core.Catalog;
import core.ColumnDef;
import core.ColumnType;
import core.Condition;
import core.Table;
import core.TableSchema;
import core.domainExceptions.DuplicateKeyException;
import core.domainExceptions.NullableKeyException;
import index.IndexDefinition;
import index.IndexKind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class Main {

    private static int passed = 0;
    private static int failed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path demoDir = Path.of("db_demo");
        Path perfDir = Path.of("db_perf");
        wipeDirectory(demoDir);
        wipeDirectory(perfDir);

        try (Catalog catalog = new Catalog(demoDir.resolve("catalog.txt"), demoDir)) {
            demoSetup(catalog);
            demoInsert(catalog);
            demoSelect(catalog);
            demoCountExists(catalog);
            demoUpdate(catalog);
            demoDelete(catalog);
            demoIndexConsistency(catalog, "после мутаций");
        }

        try (Catalog catalog = new Catalog(demoDir.resolve("catalog.txt"), demoDir)) {
            demoPersistence(catalog);
            demoCompact(catalog);
            demoDdl(catalog);          // внутри проверяем consistency только для cars
        }

        demoPerformance(perfDir);

        printSummary();
        if (failed > 0) System.exit(1);
    }

    private static void demoSetup(Catalog cat) throws Exception {
        section("SETUP: создание таблиц и индексов");

        cat.createTable("cars", new ColumnDef[]{
                new ColumnDef("id",    ColumnType.INT,    false),
                new ColumnDef("brand", ColumnType.STRING, false),
                new ColumnDef("price", ColumnType.DOUBLE, true),
                new ColumnDef("sold",  ColumnType.BOOLEAN, true)
        }, List.of(
                IndexDefinition.hash("brand", false),
                IndexDefinition.tree("price", false)
        ));

        cat.createTable("users", new ColumnDef[]{
                new ColumnDef("id",    ColumnType.INT,    false),
                new ColumnDef("login", ColumnType.STRING, false)
        });

        check("таблица cars существует", cat.hasTable("cars"));
        check("таблица users существует", cat.hasTable("users"));

        Table cars = cat.getTable("cars");
        check("cars: индекс brand (HASH)",        cars.hasIndexOn("brand"));
        check("cars: индекс price (TREE)",        cars.hasIndexOn("price"));
        check("cars: PK-индекс id автоматически", cars.hasIndexOn("id"));
        checkEq("cars: индексов всего 3", 3, cars.getIndexes().size());

        check("users: PK-индекс id автоматически",
                cat.getTable("users").hasIndexOn("id"));
        checkEq("users: индексов 1", 1, cat.getTable("users").getIndexes().size());

        Path dir = cars.getDataFile().getParent();
        check("catalog.txt создан",  Files.exists(dir.resolve("catalog.txt")));
        check("cars.dat создан",      Files.exists(cars.getDataFile()));
        check("cars.idx.id создан",    Files.exists(dir.resolve("cars.idx.id")));
        check("cars.idx.brand создан", Files.exists(dir.resolve("cars.idx.brand")));
        check("cars.idx.price создан", Files.exists(dir.resolve("cars.idx.price")));
    }

    private static void demoInsert(Catalog cat) throws Exception {
        section("INSERT: вставка и проверка уникальности");
        Table cars = cat.getTable("cars");

        cars.insert(Map.of("id", 1, "brand", "Toyota", "price", 25000.0, "sold", false));
        cars.insert(Map.of("id", 2, "brand", "BMW",    "price", 30000.0, "sold", true));
        cars.insert(Map.of("id", 3, "brand", "Audi",   "price", 40000.0, "sold", false));
        cars.insert(Map.of("id", 4, "brand", "Toyota", "price", 27000.0, "sold", true));
        cars.insert(Map.of("id", 5, "brand", "BMW",    "price", 35000.0, "sold", false));
        cars.insert(Map.of("id", 6, "brand", "Kia"));  // price, sold = null

        checkEq("cars: 6 записей", 6, cars.selectAll().size());

        expectThrows(DuplicateKeyException.class,
                () -> cars.insert(Map.of("id", 1, "brand", "Honda")),
                "дубликат PK отклонён");

        expectThrows(NullableKeyException.class,
                () -> cars.insert(Map.of("brand", "Honda")),
                "null PK отклонён");

        expectThrows(IllegalArgumentException.class,
                () -> cars.insert(Map.of("id", 99, "prcie", 1.0)),
                "опечатка в колонке отклонена");

        // ← ИСПРАВЛЕНО: Map.of не принимает null, используем HashMap
        Map<String, Object> bad = new HashMap<>();
        bad.put("id", 100);
        bad.put("brand", null);
        expectThrows(IllegalArgumentException.class,
                () -> cars.insert(bad),
                "null в nullable=false отклонён");

        Table users = cat.getTable("users");
        users.insert(Map.of("id", 1, "login", "alice"));
        users.insert(Map.of("id", 2, "login", "bob"));
        checkEq("users: 2 записи", 2, users.selectAll().size());
    }

    private static void demoSelect(Catalog cat) throws Exception {
        section("SELECT: индексы и fallback на скан");
        Table cars = cat.getTable("cars");

        checkEq("WHERE brand='BMW' → 2", 2,
                cars.select(new Condition.Eq("brand", "BMW")).size());

        var one = cars.select(new Condition.Eq("id", 3));
        checkEq("WHERE id=3 → 1", 1, one.size());
        checkEq("  brand=3 → Audi", "Audi", one.get(0).get("brand"));

        // ← ИСПРАВЛЕНО: >30000 даёт 2 (40000, 35000), не 3
        checkEq("WHERE price>30000 → 2", 2,
                cars.select(new Condition.Gt("price", 30000.0)).size());

        checkEq("WHERE 26000<=price<=35000 → 3", 3,
                cars.select(new Condition.And(
                        new Condition.Ge("price", 26000.0),
                        new Condition.Le("price", 35000.0))).size());

        var bmwExp = cars.select(new Condition.And(
                new Condition.Eq("brand", "BMW"),
                new Condition.Gt("price", 32000.0)));
        checkEq("WHERE brand='BMW' AND price>32000 → 1", 1, bmwExp.size());
        checkEq("  id=5", 5, bmwExp.get(0).get("id"));

        checkEq("WHERE brand='BMW' OR brand='Audi' → 3", 3,
                cars.select(new Condition.Or(
                        new Condition.Eq("brand", "BMW"),
                        new Condition.Eq("brand", "Audi"))).size());

        var kia = cars.select(new Condition.Eq("id", 6));
        checkEq("WHERE id=6 → 1 (nullable поля = null)", 1, kia.size());
        check("  price=null", kia.get(0).get("price") == null);
        check("  sold=null",  kia.get(0).get("sold")  == null);

        checkEq("SELECT * → 6", 6, cars.selectAll().size());
    }

    private static void demoCountExists(Catalog cat) throws Exception {
        section("COUNT / EXISTS");
        Table cars = cat.getTable("cars");

        checkEq("COUNT WHERE brand='Toyota' → 2", 2L,
                cars.count(new Condition.Eq("brand", "Toyota")));
        checkEq("COUNT WHERE price>100000 → 0", 0L,
                cars.count(new Condition.Gt("price", 100000.0)));

        // ← ИСПРАВЛЕНО: после update id=2 → 33000, поэтому >26000 → 4
        checkEq("COUNT WHERE price>26000 → 4", 4L,
                cars.count(new Condition.Gt("price", 26000.0)));

        check("EXISTS WHERE id=3 → true",
                cars.exists(new Condition.Eq("id", 3)));
        check("EXISTS WHERE id=999 → false",
                !cars.exists(new Condition.Eq("id", 999)));
    }

    private static void demoUpdate(Catalog cat) throws Exception {
        section("UPDATE: через Condition и Predicate");
        Table cars = cat.getTable("cars");

        checkEq("UPDATE WHERE id=2 → 1", 1,
                cars.update(new Condition.Eq("id", 2), Map.of("price", 33000.0)));
        checkEq("  price=33000", 33000.0,
                cars.select(new Condition.Eq("id", 2)).get(0).get("price"));

        checkEq("UPDATE WHERE brand='Toyota' (Predicate) → 2", 2,
                cars.update(row -> "Toyota".equals(row.get("brand")),
                        Map.of("price", 26000.0)));

        expectThrows(DuplicateKeyException.class,
                () -> cars.update(new Condition.Eq("id", 3), Map.of("id", 1)),
                "UPDATE на существующий PK отклонён");

        // ← ИСПРАВЛЕНО: null через HashMap
        Map<String, Object> nullBrand = new HashMap<>();
        nullBrand.put("brand", null);
        expectThrows(IllegalArgumentException.class,
                () -> cars.update(new Condition.Eq("id", 3), nullBrand),
                "UPDATE null в nullable=false отклонён");

        expectThrows(IllegalArgumentException.class,
                () -> cars.update(new Condition.Eq("id", 3), Map.of("nope", 1)),
                "UPDATE с несуществующей колонкой отклонён");

        check("индексы консистентны после UPDATE", cars.checkIndexConsistency());
    }

    private static void demoDelete(Catalog cat) throws Exception {
        section("DELETE: через Condition и Predicate");
        Table cars = cat.getTable("cars");

        long tombBefore = cars.getStats().tombstoneRecords();

        checkEq("DELETE WHERE id=5 → 1", 1,
                cars.delete(new Condition.Eq("id", 5)));
        checkEq("  осталось 5", 5, cars.selectAll().size());

        checkEq("DELETE WHERE brand='Toyota' (Predicate) → 2", 2,
                cars.delete(row -> "Toyota".equals(row.get("brand"))));
        checkEq("  осталось 3", 3, cars.selectAll().size());

        checkEq("tombstone вырос на 3",
                tombBefore + 3, cars.getStats().tombstoneRecords());

        checkEq("DELETE WHERE id=5 (уже удалён) → 0", 0,
                cars.delete(new Condition.Eq("id", 5)));
    }

    private static void demoIndexConsistency(Catalog cat, String label) throws Exception {
        section("checkIndexConsistency (" + label + ")");
        check("cars: индексы консистентны",
                cat.getTable("cars").checkIndexConsistency());
        if (cat.hasTable("users")) {
            check("users: индексы консистентны",
                    cat.getTable("users").checkIndexConsistency());
        }
    }

    private static void demoPersistence(Catalog cat) throws Exception {
        section("PERSISTENCE: перезапуск каталога");
        check("cars существует после перезапуска", cat.hasTable("cars"));
        check("users существует после перезапуска", cat.hasTable("users"));

        Table cars = cat.getTable("cars");
        checkEq("cars: 3 записи", 3, cars.selectAll().size());
        // ← ИСПРАВЛЕНО: 3 (от update) + 3 (от delete) = 6
        checkEq("cars: tombstone сохранены", 6L, cars.getStats().tombstoneRecords());

        check("индекс brand загружен", cars.hasIndexOn("brand"));
        check("индекс price загружен", cars.hasIndexOn("price"));
        check("индекс id (PK) загружен", cars.hasIndexOn("id"));

        // ← ИСПРАВЛЕНО: BMW после рестарта = 1 (id=5 удалён)
        checkEq("SELECT WHERE brand='BMW' после рестарта → 1", 1,
                cars.select(new Condition.Eq("brand", "BMW")).size());

        // ← ИСПРАВЛЕНО: price>32000: id=2 (33000), id=3 (40000) → 2
        checkEq("SELECT WHERE price>32000 после рестарта → 2", 2,
                cars.select(new Condition.Gt("price", 32000.0)).size());
    }

    private static void demoCompact(Catalog cat) throws Exception {
        section("COMPACT: переписать без tombstone");
        Table cars = cat.getTable("cars");
        long sizeBefore = cars.getStats().fileSizeBytes();
        long tombBefore = cars.getStats().tombstoneRecords();
        long liveBefore = cars.getStats().liveRecords();

        check("tombstone > 0 перед compact", tombBefore > 0);

        int removed = cars.compact();
        checkEq("compact удалил tombstone", (int) tombBefore, removed);
        checkEq("tombstone после compact = 0", 0L,
                cars.getStats().tombstoneRecords());
        checkEq("живых записей столько же", liveBefore,
                cars.getStats().liveRecords());
        check("размер уменьшился",
                cars.getStats().fileSizeBytes() < sizeBefore);
        check("индексы консистентны после compact",
                cars.checkIndexConsistency());

        checkEq("повторный compact → 0", 0, cars.compact());
    }

    private static void demoDdl(Catalog cat) throws Exception {
        section("DDL: CREATE/DROP INDEX, DROP TABLE");
        Table cars = cat.getTable("cars");

        cat.dropIndex("cars", "brand");
        check("индекса brand нет", !cars.hasIndexOn("brand"));
        // ← ИСПРАВЛЕНО: BMW живой 1
        checkEq("SELECT WHERE brand='BMW' (scan) → 1", 1,
                cars.select(new Condition.Eq("brand", "BMW")).size());

        cat.createIndex("cars", "brand", IndexKind.HASH, false);
        check("индекс brand пересоздан", cars.hasIndexOn("brand"));
        check("индексы консистентны", cars.checkIndexConsistency());

        expectThrows(IllegalArgumentException.class,
                () -> cat.dropIndex("cars", "id"),
                "DROP INDEX на PK отклонён");

        Path usersDat = cat.getTable("users").getDataFile();
        Path usersIdx = usersDat.getParent().resolve("users.idx.id");
        check("users.dat есть", Files.exists(usersDat));
        check("users.idx.id есть", Files.exists(usersIdx));

        cat.dropTable("users");
        check("users удалён из каталога", !cat.hasTable("users"));
        check("users.dat удалён",       !Files.exists(usersDat));
        check("users.idx.id удалён",    !Files.exists(usersIdx));
        check("cars остался", cat.hasTable("cars"));

        try (Catalog c2 = new Catalog(
                cars.getDataFile().getParent().resolve("catalog.txt"),
                cars.getDataFile().getParent())) {
            check("users не восстановился", !c2.hasTable("users"));
            check("cars восстановился", c2.hasTable("cars"));
        }
    }

    private static void demoPerformance(Path dir) throws Exception {
        section("PERFORMANCE: 20 000 записей, с индексом и без");
        int N = 20_000;
        Path indexedFile = dir.resolve("idx.dat");
        Path scanFile    = dir.resolve("scan.dat");

        ColumnDef[] cols = {
                new ColumnDef("id",    ColumnType.INT,    false),
                new ColumnDef("brand", ColumnType.STRING, false),
                new ColumnDef("price", ColumnType.DOUBLE, true)
        };

        try (Table withIdx = Table.create(indexedFile,
                new TableSchema("idx_cars", cols),
                List.of(IndexDefinition.hash("brand", false)));
             Table noIdx   = Table.create(scanFile,
                     new TableSchema("scan_cars", cols))) {

            long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) {
                Map<String, Object> row = Map.of(
                        "id", i,
                        "brand", "B" + (i % 20),
                        "price", (double) i);
                withIdx.insert(row);
                noIdx.insert(row);
            }
            long tIns = System.nanoTime() - t0;
            System.out.printf("  insert %d×2 строк: %d ms%n", N, ms(tIns));

            long best = Long.MAX_VALUE;
            for (int r = 0; r < 10; r++) {
                long s = System.nanoTime();
                withIdx.select(new Condition.Eq("brand", "B7"));
                best = Math.min(best, System.nanoTime() - s);
            }
            long tIdx = best;
            System.out.printf("  SELECT WHERE brand='B7' (index): %d μs%n", us(tIdx));

            best = Long.MAX_VALUE;
            for (int r = 0; r < 5; r++) {
                long s = System.nanoTime();
                noIdx.select(new Condition.Eq("brand", "B7"));
                best = Math.min(best, System.nanoTime() - s);
            }
            long tScan = best;
            System.out.printf("  SELECT WHERE brand='B7' (scan):  %d μs%n", us(tScan));
            System.out.printf("  → ускорение: ~%.0f×%n",
                    (double) tScan / Math.max(tIdx, 1));

            check("SELECT через индекс быстрее скана", tIdx < tScan);
            checkEq("  записей B7: 1000", N / 20,
                    withIdx.select(new Condition.Eq("brand", "B7")).size());
            checkEq("  scan-версия вернула столько же", N / 20,
                    noIdx.select(new Condition.Eq("brand", "B7")).size());
        }
    }

    private static void section(String title) { System.out.println("\n=== " + title + " ==="); }

    private static void check(String desc, boolean cond) {
        if (cond) { passed++; System.out.println("  ✓ " + desc); }
        else { failed++; failures.add(desc); System.out.println("  ✗ FAIL: " + desc); }
    }

    private static void checkEq(String desc, Object expected, Object actual) {
        if (Objects.equals(expected, actual)) {
            passed++; System.out.println("  ✓ " + desc);
        } else {
            failed++;
            String f = desc + " (expected=" + expected + ", actual=" + actual + ")";
            failures.add(f);
            System.out.println("  ✗ FAIL: " + f);
        }
    }

    private static <T extends Throwable> void expectThrows(
            Class<T> type, ThrowingRunnable r, String desc) {
        try {
            r.run();
            failed++; failures.add(desc + " (no exception)");
            System.out.println("  ✗ FAIL: " + desc + " (no exception)");
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                passed++;
                System.out.println("  ✓ " + desc + " → " + t.getClass().getSimpleName());
            } else {
                failed++;
                String f = desc + " (expected " + type.getSimpleName()
                        + ", got " + t.getClass().getSimpleName() + ")";
                failures.add(f);
                System.out.println("  ✗ FAIL: " + f);
            }
        }
    }

    @FunctionalInterface interface ThrowingRunnable { void run() throws Exception; }

    private static long ms(long ns) { return ns / 1_000_000; }
    private static long us(long ns) { return ns / 1_000; }

    private static void wipeDirectory(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        }
    }

    private static void printSummary() {
        System.out.println("\n────────────────────────────────────────");
        System.out.printf("ИТОГО: %d passed, %d failed%n", passed, failed);
        if (!failures.isEmpty()) {
            System.out.println("Проваленные:");
            for (String f : failures) System.out.println("  - " + f);
        }
    }
}