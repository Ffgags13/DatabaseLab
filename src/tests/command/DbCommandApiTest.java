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
        api.createTable("notes", notesSchema, Map.of(
                "user_id", new Link("users", Link.Behaviour.CASCADE, Link.Behaviour.RESTRICT)
        ));
    }

    @Test
    void fullWorkflow_crudAndLinksThroughApiTest() {
        api.insert("users", new Object[]{1, "alex", LocalDate.of(2026, 11, 15)});
        api.insert("users", Map.of(
                "id", 2,
                "login", "maria",
                "reg_date", "2026-11-10"
        ));

        api.insert("notes", new Object[]{10, 1, "Купить молоко"});
        api.insert("notes", new Object[]{20, 2, "Сдать лабу"});

        api.createIndex("notes", "text");

        List<Object[]> allUsers = api.getTable("users");
        Map<String, Object[]> mariaNotes = api.selectItems(
                "notes",
                new String[]{"id", "text"},
                new String[]{"user_id"},
                row -> row.eq(0, 2)
        );

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
                () -> Assertions.assertEquals(200, updatedNotes.get("user_id")[first]),
                () -> Assertions.assertTrue(api.getShortSchema("users").contains("users"))
        );
    }

    @Test
    void allCoreErrors_translatedToCommandExceptionTest() {
        api.insert("users", new Object[]{1, "alex", 20000L});
        api.insert("notes", new Object[]{10, 1, "Заметка"});

        Assertions.assertAll(
                // Несовпадение имени таблицы и схемы при создании
                () -> Assertions.assertThrows(CommandException.class, () ->
                        api.createTable("wrong_name", new TableSchema("real_name", new ColumnDef[]{
                                new ColumnDef("id", ColumnType.INT, false, true)
                        }), null)),

                // Несуществующая таблица
                () -> Assertions.assertThrows(CommandException.class, () -> api.getTable("no_such_table")),

                // Неверное количество значений при insert
                () -> Assertions.assertThrows(CommandException.class, () -> api.insert("users", new Object[]{2, "only_two"})),

                // Неверный тип данных при insert
                () -> Assertions.assertThrows(CommandException.class, () -> api.insert("users", new Object[]{"not_int", "bob", 100})),

                // Дубликат уникального поля
                () -> Assertions.assertThrows(CommandException.class, () -> api.insert("users", new Object[]{2, "alex", 100})),

                // Нарушение RESTRICT при удалении пользователя, у которого есть заметки
                () -> Assertions.assertThrows(CommandException.class, () ->
                        api.deleteItems("users", new String[]{"id"}, row -> row.eq(0, 1))),
                () -> Assertions.assertThrows(CommandException.class, () -> api.createIndex("notes", "user_id")),
                // Удаление несуществующего столбца в условии
                () -> Assertions.assertThrows(CommandException.class, () ->
                        api.deleteItems("notes", new String[]{"unknown_col"}, row -> true))
        );
    }
}
