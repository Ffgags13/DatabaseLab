package main.command;

import main.core.Catalog;

import java.nio.file.Files;
import java.nio.file.Path;

public final class Demo {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("sqldb");
        try (Catalog cat = new Catalog(dir.resolve("catalog.txt"), dir)) {
            SqlEngine sql = new SqlEngine(cat);

            sql.runAndPrint("CREATE TABLE cars (id INT, brand STRING, price DOUBLE)");
            sql.runAndPrint("INSERT INTO cars VALUES (1, 'bmw', 30000.0)");
            sql.runAndPrint("INSERT INTO cars VALUES (2, 'audi', 25000.0)");
            sql.runAndPrint("INSERT INTO cars VALUES (3, 'bmw', 15000.0)");

            sql.runAndPrint("SELECT id, brand, price FROM cars");
            sql.runAndPrint("SELECT id, price FROM cars WHERE price > 20000.0");

            sql.runAndPrint("UPDATE cars SET price = 31000.0 WHERE id = 1");
            sql.runAndPrint("DELETE FROM cars WHERE price < 20000.0");
            sql.runAndPrint("SELECT * FROM cars");
        }
    }
}