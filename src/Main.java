import core.Catalog;
import core.ColumnDef;
import core.ColumnType;
import core.Table;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class Main {
    public static void main(String[] args) throws Exception {
        Path dbDir = Path.of("db");
        Path catalogFile = dbDir.resolve("catalog.txt");

        // чистим для чистого старта
        if (Files.exists(dbDir)) {
            try (var stream = Files.walk(dbDir)) {
                stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }

        // ── 1. Создаём таблицы и заполняем ──
        try (Catalog catalog = new Catalog(catalogFile, dbDir)) {
            if (!catalog.hasTable("cars")) {
                catalog.createTable("cars", new ColumnDef[]{
                        new ColumnDef("id",    ColumnType.INT,    false),
                        new ColumnDef("brand", ColumnType.STRING, false),
                        new ColumnDef("price", ColumnType.DOUBLE, true)
                });
            }
            if (!catalog.hasTable("users")) {
                catalog.createTable("users", new ColumnDef[]{
                        new ColumnDef("id",    ColumnType.INT,    false),
                        new ColumnDef("login", ColumnType.STRING, false)
                });
            }

            Table cars = catalog.getTable("cars");
            cars.insert(Map.of("id", 1, "brand", "Toyota", "price", 25000.0));
            cars.insert(Map.of("id", 2, "brand", "BMW",    "price", 30000.0));

            Table users = catalog.getTable("users");
            users.insert(Map.of("id", 1, "login", "alice"));
            users.insert(Map.of("id", 2, "login", "bob"));

            System.out.println("Таблицы: " + catalog.listTableNames());
            System.out.println("cars:  " + cars.selectAll());
            System.out.println("users: " + users.selectAll());
        }   // ← Catalog.close() закрывает обе таблицы

        System.out.println("\n── после перезапуска ──");
        try (Catalog catalog = new Catalog(catalogFile, dbDir)) {
            System.out.println("Таблицы: " + catalog.listTableNames());
            System.out.println("cars:  " + catalog.getTable("cars").selectAll());
            System.out.println("users: " + catalog.getTable("users").selectAll());

            // drop одной таблицы
            catalog.dropTable("users");
            System.out.println("После drop: " + catalog.listTableNames());
        }
    }
}