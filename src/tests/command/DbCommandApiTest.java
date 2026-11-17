package tests.command;

import main.command.*;
import main.core.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

class DbCommandApiTest {
    @TempDir
    Path dbDir;

    private DbCommandApi api;

    @BeforeEach
    void setUp() {
        api = new DbCommandApi(dbDir);

        TableSchema usersSchema = new TableSchema("users", new ColumnDef[]{
                new ColumnDef("id", ColumnType.INT, false, true),
                new ColumnDef("login", ColumnType.STRING, false, true),
                new ColumnDef("reg_date", ColumnType.DATEDAYS, false, false)
        });
        api.createTable("users", usersSchema, null);

        TableSchema notesSchema = new TableSchema("notes", new ColumnDef[]{
                new ColumnDef("id", ColumnType.INT, false, true),
                new ColumnDef("user_id", ColumnType.INT, false, false),
                new ColumnDef("text", ColumnType.STRING, false, false)
        });
        // ВАЖНО: Database.createTable автоматически строит вторичный индекс
        // по всем FK-столбцам (кроме PK), поэтому notes.user_id уже проиндексирован.
        api.createTable("notes", notesSchema, Map.of(
                "user_id", new Link("users", Link.Behaviour.CASCADE, Link.Behaviour.RESTRICT)
        ));
    }

    @Test
    void fullWorkflow_crudAndLinksThroughApiTest() {
        // --- INSERT: массив значений ---
        api.insert("users", new Object[]{1, "alex", LocalDate.of(2026, 11, 15)});
        // --- INSERT: словарь (DATEDAYS из ISO-строки) ---
        api.insert("users", Map.of(
                "id", 2,
                "login", "maria",
                "reg_date", "2026-11-10"
        ));

        api.insert("notes", new Object[]{10, 1, "Купить молоко"});
        api.insert("notes", new Object[]{20, 2, "Сдать лабу"});

        // notes.text не проиндексирован автоматически (это не FK) — создаём явно.
        api.createIndex("notes", "text");

        List<Object[]> allUsers = api.getTable("users");

        Map<String, Object[]> mariaNotes = api.selectItems(
                "notes",
                new String[]{"id", "text"},
                new String[]{"user_id"},
                row -> row.eq(0, 2)
        );

        // Обновляем PK users: 2 -> 200. FK notes.user_id имеет onUpdate = CASCADE,
        // поэтому notes.user_id для заметки id=20 тоже станет 200.
        long updatedUsers = api.updateItems(
                "users",
                Map.of("id", 200),
                new String[]{"login"},
                row -> row.eq(0, "maria")
        );

        Map<String, Object[]> updatedNotes = api.selectItems(
                "notes",
                new String[]{"user_id"},
                new String[]{"id"},
                row -> row.eq(0, 20)
        );

        int first = 0;
        Assertions.assertAll(
                () -> Assertions.assertEquals(2, allUsers.size()),
                () -> Assertions.assertEquals("Сдать лабу", mariaNotes.get("text")[first]),
                () -> Assertions.assertEquals(1L, updatedUsers),
                // user_id пришёл как Integer после CASCADE-обновления
                () -> Assertions.assertEquals(200, ((Number) updatedNotes.get("user_id")[first]).intValue()),
                () -> Assertions.assertTrue(api.getShortSchema("users").contains("users"))
        );
    }

    @Test
    void allCoreErrors_translatedToCommandExceptionTest() {
        api.insert("users", new Object[]{1, "alex", 20000L}); // 20000L = 2024-10-04
        api.insert("notes", new Object[]{10, 1, "Заметка"});

        Assertions.assertAll(
                // 1. Имя таблицы не совпадает с именем в схеме
                () -> Assertions.assertThrows(CommandException.class, () ->
                        api.createTable("wrong_name", new TableSchema("real_name", new ColumnDef[]{
                                new ColumnDef("id", ColumnType.INT, false, true)
                        }), null)),

                // 2. Несуществующая таблица
                () -> Assertions.assertThrows(CommandException.class, () ->
                        api.getTable("no_such_table")),

                // 3. Неверное количество значений при insert
                () -> Assertions.assertThrows(CommandException.class, () ->
                        api.insert("users", new Object[]{2, "only_two"})),

                // 4. Неверный тип данных при insert (id = "not_int")
                () -> Assertions.assertThrows(CommandException.class, () ->
                        api.insert("users", new Object[]{"not_int", "bob", 100})),

                // 5. Дубликат уникального поля login
                () -> Assertions.assertThrows(CommandException.class, () ->
                        api.insert("users", new Object[]{2, "alex", 100})),

                // 6. RESTRICT: у users.id=1 есть заметка
                () -> Assertions.assertThrows(CommandException.class, () ->
                        api.deleteItems("users", new String[]{"id"}, row -> row.eq(0, 1))),

                // 7. Индекс по FK-столбцу уже создан автоматически в createTable
                () -> Assertions.assertThrows(CommandException.class, () ->
                        api.createIndex("notes", "user_id")),

                // 8. Удаление с условием по несуществующему столбцу
                () -> Assertions.assertThrows(CommandException.class, () ->
                        api.deleteItems("notes", new String[]{"unknown_col"}, row -> true))
        );
    }
}