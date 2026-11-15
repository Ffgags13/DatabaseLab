package main.core;

import main.core.domainExceptions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class Database {
    private final Path dbDir;
    private final Map<String, Table> tables = new HashMap<>();

    private record ChildRef(String childTableName, String fkColumn, Link link) {}
    private final Map<String, List<ChildRef>> incomingLinks = new HashMap<>();

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

    public Map<String, Object[]> selectItems(String tableName,
                                             String[] findingCols,
                                             String[] conditionCols,
                                             Predicate<RowView> condition) throws IOException {
        return getTable(tableName).selectItems(findingCols, conditionCols, condition);
    }
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
