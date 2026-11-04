package query;
import core.*;
import domainExceptions.DuplicateKeyException;
import domainExceptions.SchemaMismatchException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

public class Main {
    static int ok = 0, bad = 0;

    public static void main(String[] args) throws Exception {
        Path dat = Files.createTempDirectory("db").resolve("cars.dat");

        TableSchema schema = new TableSchema(List.of(
                ColumnDef.primaryKey("id",    ColumnType.INT),
                ColumnDef.required  ("brand", ColumnType.STRING),
                ColumnDef.required  ("price", ColumnType.DOUBLE),
                ColumnDef.required  ("made",  ColumnType.DATE)));

        // ─── 1. create ───
        Table t = Table.create(dat, schema);
        check("файл .dat создан",        Files.exists(dat));
        check("getName() = cars",        "cars".equals(t.getName()));
        check("getDataFile совпадает",   dat.equals(t.getDataFile()));
        check("isClosed = false",        !t.isClosed());
        check("selectAll пуст",          t.selectAll().isEmpty());

        // ─── 2. insert ───
        long off1 = t.insert(m(1, "BMW",  100, "2020-01-01"));
        long off2 = t.insert(m(2, "Audi", 200, "2021-01-01"));
        long off3 = t.insert(m(3, "BMW",  300, "2022-01-01"));
        check("offset1 = 0",             off1 == 0);
        check("offset растёт",           off2 > off1 && off3 > off2);
        check("selectAll = 3",           t.selectAll().size() == 3);

        // ─── 3. getStats ───
        TableStats st = t.getStats();
        check("live = 3",                st.liveRecords() == 3);
        check("tomb = 0",                st.tombstoneRecords() == 0);
        check("total = 3",               st.totalRecords() == 3);
        check("fileSize > 0",            st.fileSizeBytes() > 0);
        check("tombstoneRatio = 0",      Math.abs(st.tombstoneRatio()) < 1e-9);

        // ─── 4. дубликат PK ───
        try {
            t.insert(m(1, "X", 1, "2023-01-01"));
            check("дубликат PK брошен", false);
        } catch (DuplicateKeyException e) {
            check("дубликат PK брошен", true);
        }
        check("после дубля всё ещё 3",   t.selectAll().size() == 3);

        // ─── 5. null в required ───
        try {
            t.insert(m(9, null, 1, "2023-01-01"));
            check("null в required брошен", false);
        } catch (SchemaMismatchException e) {
            check("null в required брошен", true);
        }

        // ─── 6. неизвестная колонка ───
        try {
            Map<String, Object> bad = new LinkedHashMap<>();
            bad.put("id", 10);
            bad.put("unknown", "x");
            t.insert(bad);
            check("unknown брошен", false);
        } catch (SchemaMismatchException e) {
            check("unknown брошен", true);
        }

        // ─── 7. selectAll — содержимое ───
        List<Map<String, Object>> all = t.selectAll();
        Set<Integer> ids = new TreeSet<>();
        for (Map<String, Object> r : all) ids.add((Integer) r.get("id"));
        check("ids = {1,2,3}",           ids.equals(Set.of(1, 2, 3)));
        check("у записи id=2 brand=Audi",
                "Audi".equals(all.stream()
                        .filter(r -> ((Integer) r.get("id")) == 2)
                        .findFirst().get().get("brand")));

        // ─── 8. close ───
        t.close();
        check("isClosed после close",    t.isClosed());
        check("close повторно не падает", true);   // второй close — no-op
        t.close();

        // ─── 9. методы после close → IOException ───
        try {
            t.selectAll();
            check("selectAll после close → IOException", false);
        } catch (java.io.IOException e) {
            check("selectAll после close → IOException", true);
        }
        try {
            t.insert(m(4, "Kia", 400, "2023-01-01"));
            check("insert после close → IOException", false);
        } catch (java.io.IOException e) {
            check("insert после close → IOException", true);
        }

        // ─── 10. open + данные на месте ───
        try (Table t2 = Table.open(dat, schema)) {
            check("после open live = 3",
                    t2.getStats().liveRecords() == 3);
            check("после open selectAll = 3",
                    t2.selectAll().size() == 3);

            // ─── 11. create поверх существующего → IOException ───
            try {
                Table.create(dat, schema);
                check("create по существующему → IOException", false);
            } catch (java.io.IOException e) {
                check("create по существующему → IOException", true);
            }
        }

        // ─── 12. open несуществующего → IOException ───
        try {
            Table.open(dat.resolveSibling("nope.dat"), schema);
            check("open несуществующего → IOException", false);
        } catch (java.io.IOException e) {
            check("open несуществующего → IOException", true);
        }

        System.out.println("\n=================================");
        System.out.println("PASSED: " + ok + ", FAILED: " + bad);
        System.out.println("=================================");
        if (bad > 0) System.exit(1);
    }

    /** Map-строка для insert. */
    static Map<String, Object> m(int id, String brand, double price, String made) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("brand", brand);
        row.put("price", price);
        row.put("made", LocalDate.parse(made));
        return row;
    }

    static void check(String label, boolean cond) {
        if (cond) { ok++; System.out.println("[OK]   " + label); }
        else       { bad++; System.out.println("[FAIL] " + label); }
    }
}