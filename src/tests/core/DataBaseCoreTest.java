package tests.core;

import main.core.*;
import main.core.domainExceptions.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class DatabaseCoreTest {
    @TempDir
    Path dbDir;

    private Database db;

    @BeforeEach
    void setUp() throws IOException {
        db = new Database(dbDir);

        TableSchema usersSchema = new TableSchema("users", new ColumnDef[]{
                new ColumnDef("id", ColumnType.INT, false, true),
                new ColumnDef("name", ColumnType.STRING, false, false)
        });
        db.createTable(usersSchema, null);
    }

    @Test
    void createTable_validationAndExceptionsTest() {
        // 1. Дубликат имени таблицы
        TableSchema dupUsers = new TableSchema("users", new ColumnDef[]{
                new ColumnDef("id", ColumnType.INT, false, true)
        });
        Assertions.assertThrows(TableAlreadyExistsException.class, () -> db.createTable(dupUsers, null));

        // 2. Ссылка на несуществующую родительскую таблицу
        TableSchema ordersBadParent = new TableSchema("orders", new ColumnDef[]{
                new ColumnDef("id", ColumnType.INT, false, true),
                new ColumnDef("user_id", ColumnType.INT, false, false)
        });
        Map<String, Link> badParentLink = Map.of("user_id", new Link("ghost_table", Link.Behaviour.CASCADE, Link.Behaviour.CASCADE));
        Assertions.assertThrows(UnknownParentNameException.class, () -> db.createTable(ordersBadParent, badParentLink));

        // 3. Внешний ключ объявлен на несуществующий столбец
        Map<String, Link> badColLink = Map.of("missing_col", new Link("users", Link.Behaviour.CASCADE, Link.Behaviour.CASCADE));
        Assertions.assertThrows(UnknownColumnName.class, () -> db.createTable(ordersBadParent, badColLink));

        // 4. Несовпадение типа FK (STRING) и PK родителя (INT)
        TableSchema ordersBadType = new TableSchema("orders_type", new ColumnDef[]{
                new ColumnDef("id", ColumnType.INT, false, true),
                new ColumnDef("user_id", ColumnType.STRING, false, false)
        });
        Map<String, Link> badTypeLink = Map.of("user_id", new Link("users", Link.Behaviour.RESTRICT, Link.Behaviour.RESTRICT));
        Assertions.assertThrows(IncorrectType.class, () -> db.createTable(ordersBadType, badTypeLink));

        // 5. SET_NULL на non-nullable столбце
        Map<String, Link> setNullNotNullable = Map.of("user_id", new Link("users", Link.Behaviour.SET_NULL, Link.Behaviour.SET_NULL));
        Assertions.assertThrows(IllegalArgumentException.class, () -> db.createTable(ordersBadParent, setNullNotNullable));
    }

    @Test
    void foreignKeyInsertAndUpdate_validationTest() throws IOException {
        TableSchema ordersSchema = new TableSchema("orders", new ColumnDef[]{
                new ColumnDef("id", ColumnType.INT, false, true),
                new ColumnDef("user_id", ColumnType.INT, true, false), // nullable FK
                new ColumnDef("amount", ColumnType.DOUBLE, false, false)
        });
        db.createTable(ordersSchema, Map.of("user_id", new Link("users", Link.Behaviour.RESTRICT, Link.Behaviour.RESTRICT)));

        db.insert("users", new Object[]{1, "Alice"});

        // Успешная вставка с существующим user_id = 1 и с user_id = null
        db.insert("orders", new Object[]{100, 1, 500.0});
        db.insert("orders", new Object[]{101, null, 250.0});

        Assertions.assertAll(
                // Попытка вставить заказ для несуществующего пользователя 999
                () -> Assertions.assertThrows(ForeignKeyConstraintException.class,
                        () -> db.insert("orders", new Object[]{102, 999, 100.0})),

                // Попытка обновить user_id в существующем заказе на несуществующего пользователя 888
                () -> Assertions.assertThrows(ForeignKeyConstraintException.class,
                        () -> db.updateItems("orders", Map.of("user_id", 888), new String[]{"id"}, r -> r.eq(0, 100))),

                // Обращение к несуществующей таблице
                () -> Assertions.assertThrows(UnknownTableNameException.class,
                        () -> db.getAllRows("unknown_table"))
        );
    }

    @Test
    void databaseRestart_restoresTablesIndexesAndLinksTest() throws IOException {
        TableSchema ordersSchema = new TableSchema("orders", new ColumnDef[]{
                new ColumnDef("id", ColumnType.INT, false, true),
                new ColumnDef("user_id", ColumnType.INT, false, false)
        });
        db.createTable(ordersSchema, Map.of("user_id", new Link("users", Link.Behaviour.RESTRICT, Link.Behaviour.RESTRICT)));

        db.insert("users", new Object[]{1, "Alice"});
        db.insert("orders", new Object[]{10, 1});

        // Имитируем перезапуск программы: создаем новый экземпляр Database на той же папке
        Database restartedDb = new Database(dbDir);

        Assertions.assertAll(
                () -> Assertions.assertEquals(1, restartedDb.getAllRows("users").size()),
                () -> Assertions.assertEquals(1, restartedDb.getAllRows("orders").size()),
                // Граф связей incomingLinks должен восстановиться и заблокировать удаление Alice!
                () -> Assertions.assertThrows(ForeignKeyConstraintException.class,
                        () -> restartedDb.deleteItems("users", new String[]{"id"}, r -> r.eq(0, 1))),
                // Проверка вставки несуществующего FK после рестарта
                () -> Assertions.assertThrows(ForeignKeyConstraintException.class,
                        () -> restartedDb.insert("orders", new Object[]{20, 777}))
        );
    }
}
