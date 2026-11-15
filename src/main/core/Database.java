package main.core;

import main.core.domainExceptions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;
/**
 * Центральный менеджер базы данных, управляющий жизненным циклом таблиц
 * и поддержанием ссылочной целостности между ними.
 * <p>
 * Отслеживает граф внешних ключей ({@link Link}) и обеспечивает соблюдение правил
 * {@code RESTRICT}, {@code SET_NULL} и {@code CASCADE} при вставке, обновлении и удалении записей.
 */
public class Database {
    private final Path dbDir;
    private final Map<String, Table> tables = new HashMap<>();

    private record ChildRef(String childTableName, String fkColumn, Link link) {}
    private final Map<String, List<ChildRef>> incomingLinks = new HashMap<>();
    /**
     * Инициализирует экземпляр базы данных в указанной директории и загружает
     * все существующие таблицы, найденные по файлам схем.
     *
     * @param dbDir путь к директории базы данных
     * @throws IOException при ошибке создания директории или загрузки файлов таблиц
     */
    public Database(Path dbDir) throws IOException {
        this.dbDir = dbDir;
        Files.createDirectories(dbDir);
        bootTables();
    }

    private void bootTables() throws IOException {
        try (Stream<Path> stream = Files.list(dbDir)) {
            List<String> names = stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(Table.SCHEMA_SUFFIX))
                    .map(n -> n.substring(0, n.length() - Table.SCHEMA_SUFFIX.length()))
                    .toList();

            for (String name : names) {
                Table table = new Table(dbDir, name);
                tables.put(name, table);
                registerIncomingLinks(table.getSchema());
            }
        }
    }

    private void registerIncomingLinks(TableSchema schema) {
        String childName = schema.getTableName();
        for (Map.Entry<String, Link> entry : schema.getForeignKeys().entrySet()) {
            Link link = entry.getValue();
            incomingLinks
                    .computeIfAbsent(link.getParentTableName(), k -> new ArrayList<>())
                    .add(new ChildRef(childName, entry.getKey(), link));
        }
    }
    /**
     * Создаёт новую таблицу с заданной схемой и внешними ключами.
     * <p>
     * Автоматически строит вторичные индексы по всем столбцам внешних ключей (кроме PK)
     * для ускорения каскадных операций и проверок целостности.
     *
     * @param schema схема создаваемой таблицы
     * @param links  карта внешних ключей формата {@code имя_столбца -> связь} (может быть {@code null})
     * @throws IOException                 при ошибке создания файлов на диске
     * @throws TableAlreadyExistsException если таблица с таким именем уже существует
     * @throws UnknownParentNameException  если родительская таблица, указанная в связи, не найдена
     * @throws IncorrectType               если тип столбца внешнего ключа не совпадает с типом PK родителя
     * @throws IllegalArgumentException    если для правила {@code SET_NULL} выбран столбец с ограничением {@code NOT NULL}
     */
    public void createTable(TableSchema schema, Map<String, Link> links) throws IOException {
        String tableName = schema.getTableName();
        if (tables.containsKey(tableName)) {
            throw new TableAlreadyExistsException(tableName);
        }

        if (links != null && !links.isEmpty()) {
            validateLinks(schema, links);
            schema.setForeignKeys(links);
        }

        Table table = new Table(dbDir, schema);
        tables.put(tableName, table);
        registerIncomingLinks(schema);

        if (links != null) {
            for (String fkCol : links.keySet()) {
                if (!fkCol.equals(schema.pkColumn().name())) {
                    table.createIndex(fkCol);
                }
            }
        }
    }

    private void validateLinks(TableSchema childSchema, Map<String, Link> links) {
        for (Map.Entry<String, Link> entry : links.entrySet()) {
            String fkColName = entry.getKey();
            Link link = entry.getValue();

            ColumnDef fkCol = childSchema.getColumn(fkColName);
            if ((link.getOnDelete() == Link.Behaviour.SET_NULL || link.getOnUpdate() == Link.Behaviour.SET_NULL)
                    && !fkCol.nullable()) {
                throw new IllegalArgumentException("Столбец " + fkColName + " должен быть nullable для SET_NULL");
            }

            Table parent = tables.get(link.getParentTableName());
            if (parent == null) {
                throw new UnknownParentNameException(link.getParentTableName());
            }

            ColumnDef parentPk = parent.getSchema().pkColumn();
            if (fkCol.type() != parentPk.type()) {
                throw new IncorrectType(parentPk.type().name(), fkColName);
            }
        }
    }
    /**
     * Удаляет таблицу из базы данных и стирает её файлы схемы и данных с диска.
     *
     * @param tableName имя удаляемой таблицы
     * @throws IOException                   при ошибке удаления файлов с диска
     * @throws UnknownTableNameException     если таблица с таким именем не найдена
     * @throws ForeignKeyConstraintException если на таблицу ссылаются внешние ключи других таблиц
     */
    public void dropTable(String tableName) throws IOException {
        getTable(tableName);

        List<ChildRef> dependents = incomingLinks.get(tableName);
        if (dependents != null && !dependents.isEmpty()) {
            throw new ForeignKeyConstraintException(
                    "Нельзя удалить таблицу " + tableName + ", на неё ссылается " + dependents.get(0).childTableName()
            );
        }

        Table removed = tables.remove(tableName);
        for (Link link : removed.getSchema().getForeignKeys().values()) {
            List<ChildRef> parentDeps = incomingLinks.get(link.getParentTableName());
            if (parentDeps != null) {
                parentDeps.removeIf(ref -> ref.childTableName().equals(tableName));
            }
        }

        Files.deleteIfExists(dbDir.resolve(tableName + Table.DATA_SUFFIX));
        Files.deleteIfExists(dbDir.resolve(tableName + Table.SCHEMA_SUFFIX));
    }
    /**
     * Создаёт вторичный индекс по указанному столбцу таблицы.
     *
     * @param tableName имя таблицы
     * @param colName   имя индексируемого столбца
     * @throws IOException при ошибке чтения данных или сохранения схемы
     */
    public void createIndex(String tableName, String colName) throws IOException {
        getTable(tableName).createIndex(colName);
    }

    public TableSchema getSchema(String tableName) {
        return getTable(tableName).getSchema();
    }

    public String getShortSchema(String tableName) {
        return getTable(tableName).getShortSchema();
    }

    public List<Object[]> getAllRows(String tableName) throws IOException {
        return getTable(tableName).getAllRows();
    }
    /**
     * Выполняет выборку столбцов из таблицы по заданному условию.
     *
     * @param tableName     имя таблицы
     * @param findingCols   массив имён возвращаемых столбцов
     * @param conditionCols массив имён столбцов, используемых в предикате
     * @param condition     условие фильтрации строк (может быть {@code null})
     * @return словарь формата {@code имя_столбца -> массив_значений}
     * @throws IOException при ошибке чтения данных с диска
     */
    public Map<String, Object[]> selectItems(String tableName,
                                             String[] findingCols,
                                             String[] conditionCols,
                                             Predicate<RowView> condition) throws IOException {
        return getTable(tableName).selectItems(findingCols, conditionCols, condition);
    }
    /**
     * Вставляет новую запись в таблицу, переданную в виде массива значений в порядке столбцов схемы.
     *
     * @param tableName имя таблицы
     * @param values    массив значений полей
     * @throws IOException                   при ошибке записи на диск
     * @throws ForeignKeyConstraintException если значение внешнего ключа отсутствует в родительской таблице
     */
    public void insert(String tableName, Object[] values) throws IOException {
        Table table = getTable(tableName);
        if (values == null) {
            throw new IllegalArgumentException("Передан null вместо массива значений");
        }
        ColumnDef[] cols = table.getSchema().getColumns();
        if (values.length != cols.length) {
            throw new AmountValuesException(cols.length);
        }

        Map<String, Object> row = new LinkedHashMap<>(cols.length);
        for (int i = 0; i < cols.length; i++) {
            row.put(cols[i].name(), values[i]);
        }
        insert(tableName, row);
    }

    /**
     * Вставляет новую запись в таблицу с предварительной проверкой существования родительских ключей.
     *
     * @param tableName имя таблицы
     * @param row       словарь значений в формате {@code имя_столбца -> значение}
     * @throws IOException                   при ошибке записи на диск
     * @throws ForeignKeyConstraintException если значение внешнего ключа не найдено в родительской таблице
     */
    public void insert(String tableName, Map<String, Object> row) throws IOException {
        Table table = getTable(tableName);
        if (row == null) {
            throw new IllegalArgumentException("Передан null вместо словаря значений");
        }
        checkParentKeysExist(table.getSchema(), row);
        table.insert(row);
    }

    private void checkParentKeysExist(TableSchema schema, Map<String, Object> row) throws IOException {
        for (Map.Entry<String, Link> entry : schema.getForeignKeys().entrySet()) {
            Object fkVal = row.get(entry.getKey());
            if (fkVal == null) continue;

            Table parent = getTable(entry.getValue().getParentTableName());
            if (parent.selectByPk(fkVal) == null) {
                throw new ForeignKeyConstraintException(
                        "Ключ " + fkVal + " отсутствует в родительской таблице " + parent.getSchema().getTableName()
                );
            }
        }
    }
    /**
     * Обновляет записи в таблице с проверкой внешних ключей и выполнением каскадных правил
     * ({@code CASCADE}, {@code SET_NULL}, {@code RESTRICT}) при изменении первичного ключа.
     *
     * @param tableName     имя таблицы
     * @param newValues     словарь обновляемых столбцов и новых значений
     * @param conditionCols массив имён столбцов, используемых в условии
     * @param condition     предикат отбора записей для обновления
     * @return количество обновлённых записей
     * @throws IOException                   при ошибке работы с диском
     * @throws ForeignKeyConstraintException если обновление нарушает ссылочную целостность
     */
    public long updateItems(String tableName,
                            Map<String, Object> newValues,
                            String[] conditionCols,
                            Predicate<RowView> condition) throws IOException {
        Table table = getTable(tableName);
        if (newValues == null || newValues.isEmpty()) {
            throw new IllegalArgumentException("Не переданы поля для обновления");
        }

        checkParentKeysExist(table.getSchema(), newValues);

        String pkName = table.getSchema().pkColumn().name();
        if (!newValues.containsKey(pkName)) {
            return table.updateItems(newValues, conditionCols, condition);
        }

        Object newPk = newValues.get(pkName);
        Object[] oldPks = table.selectItems(new String[]{pkName}, conditionCols, condition).get(pkName);
        if (oldPks == null || oldPks.length == 0) {
            return 0L;
        }

        for (Object oldPk : oldPks) {
            if (!Objects.equals(oldPk, newPk)) {
                ensureNoRestrictBlocks(tableName, oldPk, true);
            }
        }

        long updated = table.updateItems(newValues, conditionCols, condition);

        for (Object oldPk : oldPks) {
            if (!Objects.equals(oldPk, newPk)) {
                propagateToChildren(tableName, oldPk, newPk, true);
            }
        }
        return updated;
    }

    public long deleteItems(String tableName,
                            String[] conditionCols,
                            Predicate<RowView> condition) throws IOException {
        Table table = getTable(tableName);
        String pkName = table.getSchema().pkColumn().name();

        Object[] pksToDelete = table.selectItems(new String[]{pkName}, conditionCols, condition).get(pkName);
        if (pksToDelete == null || pksToDelete.length == 0) {
            return 0L;
        }

        for (Object pk : pksToDelete) {
            ensureNoRestrictBlocks(tableName, pk, false);
        }

        long deleted = table.deleteItems(conditionCols, condition);

        for (Object pk : pksToDelete) {
            propagateToChildren(tableName, pk, null, false);
        }
        return deleted;
    }

    private void ensureNoRestrictBlocks(String parentName, Object parentPk, boolean isUpdate) throws IOException {
        List<ChildRef> dependents = incomingLinks.getOrDefault(parentName, Collections.emptyList());
        for (ChildRef ref : dependents) {
            Link.Behaviour mode = isUpdate ? ref.link().getOnUpdate() : ref.link().getOnDelete();
            if (mode == Link.Behaviour.RESTRICT) {
                Table child = getTable(ref.childTableName());
                String childPk = child.getSchema().pkColumn().name();
                Object[] matches = child.selectItems(
                        new String[]{childPk},
                        new String[]{ref.fkColumn()},
                        row -> row.eq(0, parentPk)
                ).get(childPk);

                if (matches.length > 0) {
                    throw new ForeignKeyConstraintException(
                            "Запрещено (RESTRICT): запись " + parentPk + " используется в таблице " + ref.childTableName()
                    );
                }
            }
        }
    }

    private void propagateToChildren(String parentName, Object oldPk, Object newPk, boolean isUpdate) throws IOException {
        List<ChildRef> dependents = incomingLinks.getOrDefault(parentName, Collections.emptyList());
        for (ChildRef ref : dependents) {
            Link.Behaviour mode = isUpdate ? ref.link().getOnUpdate() : ref.link().getOnDelete();
            String childName = ref.childTableName();
            String fkCol = ref.fkColumn();

            switch (mode) {
                case CASCADE -> {
                    if (isUpdate) {
                        updateItems(childName, Map.of(fkCol, newPk), new String[]{fkCol}, row -> row.eq(0, oldPk));
                    } else {
                        deleteItems(childName, new String[]{fkCol}, row -> row.eq(0, oldPk));
                    }
                }
                case SET_NULL -> {
                    Map<String, Object> nullMap = new HashMap<>();
                    nullMap.put(fkCol, null);
                    getTable(childName).updateItems(nullMap, new String[]{fkCol}, row -> row.eq(0, oldPk));
                }
                case RESTRICT -> { }
            }
        }
    }

    private Table getTable(String tableName) {
        Table table = tables.get(tableName);
        if (table == null) {
            throw new UnknownTableNameException(tableName);
        }
        return table;
    }
}
