package tests.core;

import main.core.*;
import main.core.domainExceptions.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class TableTest {
    @TempDir
    Path dbDir;

    private Table table;

    @BeforeEach
    void setUp() throws IOException {
        ColumnDef[] cols = new ColumnDef[]{
                new ColumnDef("id", ColumnType.INT, false, true),
                new ColumnDef("username", ColumnType.STRING, false, true),
                new ColumnDef("age", ColumnType.INT, false, false),
                new ColumnDef("bio", ColumnType.STRING, true, false)
        };
        TableSchema schema = new TableSchema("users", cols);
        table = new Table(dbDir, schema);
    }

    @Test
    void primaryIndex_persistenceAndCompactionTest() throws IOException {
        table.insert(new Object[]{1, "alice", 22, "Java dev"});
        table.insert(new Object[]{2, "bob", 30, "Python dev"});
        table.insert(new Object[]{3, "charlie", 25, null});

        // 1. Поиск по PK работает до закрытия
        Map<String, Object> alice = table.selectByPk(1);
        Assertions.assertNotNull(alice);
        Assertions.assertEquals("alice", alice.get("username"));

        // 2. Удаляем вторую запись и проверяем, что индекс обновился
        table.deleteItems(new String[]{"id"}, row -> row.eq(0, 2));
        Assertions.assertNull(table.selectByPk(2));

        // 3. Сжимаем файл (смещения записей в файле изменятся!) и проверяем, что индекс перестроился
        table.compact();
        Map<String, Object> charlieAfterCompact = table.selectByPk(3);
        Assertions.assertNotNull(charlieAfterCompact);
        Assertions.assertEquals("charlie", charlieAfterCompact.get("username"));
        Assertions.assertNull(charlieAfterCompact.get("bio"));

        // 4. Переоткрываем таблицу заново с диска (проверка прогрева primaryIndex из файла)
        Table reopened = new Table(dbDir, "users");
        Assertions.assertAll(
                () -> Assertions.assertNotNull(reopened.selectByPk(1)),
                () -> Assertions.assertNull(reopened.selectByPk(2)),
                () -> Assertions.assertNotNull(reopened.selectByPk(3)),
                () -> Assertions.assertThrows(NonUniqueValueException.class,
                        () -> reopened.insert(new Object[]{1, "new_alice", 40, null}))
        );
    }

    @Test
    void insert_allDomainExceptionsTest() throws IOException {
        table.insert(new Object[]{1, "alice", 22, "ok"});

        // Строка длиннее Short.MAX_VALUE (32767 байт)
        String tooLongString = "a".repeat(Short.MAX_VALUE + 1);

        Map<String, Object> mapWithUnknownCol = new HashMap<>();
        mapWithUnknownCol.put("id", 10);
        mapWithUnknownCol.put("username", "test");
        mapWithUnknownCol.put("age", 20);
        mapWithUnknownCol.put("non_existent_col", 123);

        Map<String, Object> mapWithNullPk = new HashMap<>();
        mapWithNullPk.put("id", null);
        mapWithNullPk.put("username", "test");
        mapWithNullPk.put("age", 20);

        Assertions.assertAll(
                // Null вместо массива или словаря
                () -> Assertions.assertThrows(IllegalArgumentException.class, () -> table.insert((Object[]) null)),
                () -> Assertions.assertThrows(IllegalArgumentException.class, () -> table.insert((Map<String, Object>) null)),

                // Неверное количество элементов в массиве
                () -> Assertions.assertThrows(AmountValuesException.class, () -> table.insert(new Object[]{2, "bob"})),

                // Несуществующий столбец в Map
                () -> Assertions.assertThrows(UnknownColumnName.class, () -> table.insert(mapWithUnknownCol)),

                // Null в PK и в NOT NULL столбце
                () -> Assertions.assertThrows(NullValueException.class, () -> table.insert(mapWithNullPk)),
                () -> Assertions.assertThrows(NullValueException.class, () -> table.insert(new Object[]{2, null, 20, null})),

                // Неправильный тип данных при вставке
                () -> Assertions.assertThrows(IncorrectType.class, () -> table.insert(new Object[]{2, "bob", "not_an_int", null})),

                // Превышение длины строки
                () -> Assertions.assertThrows(MaxLengthExceededException.class, () -> table.insert(new Object[]{2, "bob", 20, tooLongString})),

                // Дубликат PK (проверка через primaryIndex) и дубликат обычного unique-поля
                () -> Assertions.assertThrows(NonUniqueValueException.class, () -> table.insert(new Object[]{1, "bob", 20, null})),
                () -> Assertions.assertThrows(NonUniqueValueException.class, () -> table.insert(new Object[]{2, "alice", 20, null}))
        );
    }

    @Test
    void selectItems_filteringAndExceptionsTest() throws IOException {
        table.insert(new Object[]{1, "alice", 22, "bio1"});
        table.insert(new Object[]{2, "bob", 17, "bio2"});
        table.insert(new Object[]{3, "charlie", 28, null});

        Map<String, Object[]> res = table.selectItems(
                new String[]{"id", "username"},
                new String[]{"age", "bio"},
                row -> row.gt(0, 18) && !row.isNull(1)
        );

        Assertions.assertAll(
                () -> Assertions.assertArrayEquals(new Object[]{1}, res.get("id")),
                () -> Assertions.assertArrayEquals(new Object[]{"alice"}, res.get("username")),
                () -> Assertions.assertThrows(UnknownColumnName.class,
                        () -> table.selectItems(new String[]{"wrong_col"}, null, null)),
                () -> Assertions.assertThrows(UnknownColumnName.class,
                        () -> table.selectItems(new String[]{"id"}, new String[]{"wrong_cond_col"}, row -> true)),
                () -> Assertions.assertThrows(IncorrectTypeInConditionException.class,
                        () -> table.selectItems(new String[]{"id"}, new String[]{"age"}, row -> row.gt(0, "abc")))
        );
    }

    @Test
    void updateItems_rollbackOnErrorAndSuccessTest() throws IOException {
        table.insert(new Object[]{1, "alice", 22, "old_bio"});
        table.insert(new Object[]{2, "bob", 30, "bob_bio"});

        // 1. Попытка обновить username у bob на уже занятый "alice" -> должно выбросить NonUniqueValueException
        // и НЕ потерять запись bob!
        Assertions.assertThrows(NonUniqueValueException.class, () ->
                table.updateItems(
                        Map.of("username", "alice"),
                        new String[]{"id"},
                        row -> row.eq(0, 2)
                )
        );

        // Проверяем, что bob остался жив в таблице и в primaryIndex
        Map<String, Object> bobAfterFailedUpdate = table.selectByPk(2);
        Assertions.assertNotNull(bobAfterFailedUpdate);
        Assertions.assertEquals("bob", bobAfterFailedUpdate.get("username"));

        // 2. Попытка передать неверный тип в updateItems -> IncorrectType
        Assertions.assertThrows(IncorrectType.class, () ->
                table.updateItems(
                        Map.of("age", "twenty"),
                        new String[]{"id"},
                        row -> row.eq(0, 1)
                )
        );

        // 3. Успешное обновление с изменением PK (проверка перестройки primaryIndex)
        Map<String, Object> validUpdates = new LinkedHashMap<>();
        validUpdates.put("id", 100);
        validUpdates.put("age", 23);

        long updatedCount = table.updateItems(
                validUpdates,
                new String[]{"username"},
                row -> row.eq(0, "alice")
        );

        Assertions.assertAll(
                () -> Assertions.assertEquals(1L, updatedCount),
                () -> Assertions.assertNull(table.selectByPk(1)),
                () -> Assertions.assertNotNull(table.selectByPk(100)),
                () -> Assertions.assertEquals(23, table.selectByPk(100).get("age"))
        );
    }

    @Test
    void deleteItems_exceptionsAndCountTest() throws IOException {
        table.insert(new Object[]{1, "alice", 22, null});
        table.insert(new Object[]{2, "bob", 30, null});
        table.insert(new Object[]{3, "charlie", 35, null});

        long deleted = table.deleteItems(
                new String[]{"age"},
                row -> row.gt(0, 25)
        );

        Assertions.assertAll(
                () -> Assertions.assertEquals(2L, deleted),
                () -> Assertions.assertEquals(1, table.getAllRows().size()),
                () -> Assertions.assertNotNull(table.selectByPk(1)),
                () -> Assertions.assertNull(table.selectByPk(2)),
                () -> Assertions.assertThrows(UnknownColumnName.class,
                        () -> table.deleteItems(new String[]{"bad_col"}, row -> true)),
                () -> Assertions.assertThrows(IncorrectTypeInConditionException.class,
                        () -> table.deleteItems(new String[]{"age"}, row -> row.lt(0, "not_int")))
        );
    }
}
