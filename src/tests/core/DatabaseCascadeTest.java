package tests.core;

import main.core.*;
import main.core.domainExceptions.ForeignKeyConstraintException;
import main.core.domainExceptions.UnknownTableNameException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class DatabaseCascadeTest {
    @TempDir
    Path dbDir;

    private Database db;

    @BeforeEach
    void setUp() throws IOException {
        db = new Database(dbDir);

        // 1. Родительская таблица: users
        db.createTable(new TableSchema("users", new ColumnDef[]{
                new ColumnDef("id", ColumnType.INT, false, true),
                new ColumnDef("username", ColumnType.STRING, false, true)
        }), null);

        // 2. Дочерняя таблица 1: orders (CASCADE при удалении и обновлении user_id)
        db.createTable(new TableSchema("orders", new ColumnDef[]{
                new ColumnDef("id", ColumnType.INT, false, true),
                new ColumnDef("user_id", ColumnType.INT, false, false),
                new ColumnDef("title", ColumnType.STRING, false, false)
        }), Map.of("user_id", new Link("users", Link.Behaviour.CASCADE, Link.Behaviour.CASCADE)));

        // 3. Дочерняя таблица 2 (внучатая): order_items (ссылается на orders -> CASCADE)
        db.createTable(new TableSchema("order_items", new ColumnDef[]{
                new ColumnDef("id", ColumnType.INT, false, true),
                new ColumnDef("order_id", ColumnType.INT, false, false),
                new ColumnDef("product", ColumnType.STRING, false, false)
        }), Map.of("order_id", new Link("orders", Link.Behaviour.CASCADE, Link.Behaviour.CASCADE)));

        // 4. Дочерняя таблица 3: user_logs (SET_NULL при удалении и обновлении user_id)
        db.createTable(new TableSchema("user_logs", new ColumnDef[]{
                new ColumnDef("id", ColumnType.INT, false, true),
                new ColumnDef("user_id", ColumnType.INT, true, false),
                new ColumnDef("action", ColumnType.STRING, false, false)
        }), Map.of("user_id", new Link("users", Link.Behaviour.SET_NULL, Link.Behaviour.SET_NULL)));

        // Наполняем тестовыми данными
        db.insert("users", new Object[]{1, "alice"});
        db.insert("users", new Object[]{2, "bob"});

        db.insert("orders", new Object[]{10, 1, "Alice Order 1"});
        db.insert("orders", new Object[]{20, 2, "Bob Order 1"});

        db.insert("order_items", new Object[]{100, 10, "Laptop"});
        db.insert("order_items", new Object[]{101, 10, "Mouse"});
        db.insert("order_items", new Object[]{200, 20, "Keyboard"});

        db.insert("user_logs", new Object[]{1000, 1, "LOGIN"});
        db.insert("user_logs", new Object[]{2000, 2, "LOGOUT"});
    }

    @Test
    void multiLevelCascadeAndSetNullOnDeleteTest() throws IOException {
        // Удаляем Alice (id = 1). Ожидаем:
        // - Из orders удалится заказ 10 (CASCADE)
        // - Из order_items автоматически удалятся товары 100 и 101 (многоуровневый CASCADE!)
        // - В user_logs у записи 1000 поле user_id станет null (SET_NULL)
        // - Данные Bob (id = 2) останутся полностью нетронутыми
        long deletedUsers = db.deleteItems("users", new String[]{"id"}, r -> r.eq(0, 1));

        Map<String, Object[]> aliceLog = db.selectItems(
                "user_logs",
                new String[]{"user_id", "action"},
                new String[]{"id"},
                r -> r.eq(0, 1000)
        );

        Assertions.assertAll(
                () -> Assertions.assertEquals(1L, deletedUsers),
                () -> Assertions.assertEquals(1, db.getAllRows("users").size()),
                () -> Assertions.assertEquals(1, db.getAllRows("orders").size()),
                () -> Assertions.assertEquals(1, db.getAllRows("order_items").size()),
                () -> Assertions.assertNull(aliceLog.get("user_id")[0]),
                () -> Assertions.assertEquals("LOGIN", aliceLog.get("action")[0])
        );
    }

    @Test
    void cascadeAndSetNullOnPrimaryKeyUpdateTest() throws IOException {
        // Меняем PK у Bob с 2 на 222. Ожидаем:
        // - В orders у заказа 20 поле user_id обновится на 222 (CASCADE)
        // - В user_logs у записи 2000 поле user_id сбросится в null (SET_NULL)
        long updated = db.updateItems("users", Map.of("id", 222), new String[]{"id"}, r -> r.eq(0, 2));

        Map<String, Object[]> bobOrders = db.selectItems(
                "orders",
                new String[]{"id", "user_id"},
                new String[]{"user_id"},
                r -> r.eq(0, 222)
        );

        Map<String, Object[]> bobLog = db.selectItems(
                "user_logs",
                new String[]{"user_id"},
                new String[]{"id"},
                r -> r.eq(0, 2000)
        );

        Assertions.assertAll(
                () -> Assertions.assertEquals(1L, updated),
                () -> Assertions.assertEquals(1, bobOrders.get("id").length),
                () -> Assertions.assertEquals(222, bobOrders.get("user_id")[0]),
                () -> Assertions.assertNull(bobLog.get("user_id")[0])
        );
    }

    @Test
    void dropTable_enforcesDependencyOrderTest() throws IOException {
        // Нельзя удалить users или orders, пока на них есть ссылки
        Assertions.assertThrows(ForeignKeyConstraintException.class, () -> db.dropTable("users"));
        Assertions.assertThrows(ForeignKeyConstraintException.class, () -> db.dropTable("orders"));

        // Удаляем таблицы в правильном порядке (от листьев к корню)
        db.dropTable("order_items");
        db.dropTable("orders");
        db.dropTable("user_logs");
        db.dropTable("users");

        // Проверяем, что таблица users действительно удалена
        Assertions.assertThrows(UnknownTableNameException.class, () -> db.getAllRows("users"));
    }
}
