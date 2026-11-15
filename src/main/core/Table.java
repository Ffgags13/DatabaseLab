package main.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import main.core.domainExceptions.NonUniqueValueException;
import main.core.domainExceptions.UnknownColumnName;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Predicate;

public class Table {
    private final Path dataPath;
    private final Path schemaPath;
    private final TableSchema schema;

    // Главный индекс в памяти: PK -> Offset в файле данных
    private final Map<Object, Long> primaryIndex = new HashMap<>();

    public static final String SCHEMA_SUFFIX = ".schema.json";
    public static final String DATA_SUFFIX = ".dat";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final int MIN_RECORDS_FOR_COMPACT = 100;
    private static final double TOMBSTONE_RATIO_THRESHOLD = 0.3;
    private long totalRecords = 0;
    private long tombstoneCount = 0;

    // Создание НОВОЙ таблицы
    public Table(Path dbDirectory, TableSchema schema) throws IOException {
        Files.createDirectories(dbDirectory);

        String tableName = schema.getTableName();
        this.dataPath = dbDirectory.resolve(tableName + DATA_SUFFIX);
        this.schemaPath = dbDirectory.resolve(tableName + SCHEMA_SUFFIX);
        this.schema = schema;

        saveSchemaToDisk();

        if (Files.notExists(this.dataPath)) {
            Files.createFile(this.dataPath);
        }
    }

    //Открытие СУЩЕСТВУЮЩЕЙ таблицы при старте БД
    public Table(Path dbDirectory, String tableName) throws IOException {
        this.dataPath = dbDirectory.resolve(tableName + DATA_SUFFIX);
        this.schemaPath = dbDirectory.resolve(tableName + SCHEMA_SUFFIX);

        if (Files.notExists(this.schemaPath) || Files.notExists(this.dataPath)) {
            throw new IllegalStateException("Файлы таблицы '" + tableName + "' не найдены в " + dbDirectory);
        }

        this.schema = loadSchemaFromDisk();
        loadTableState();
    }

    private void saveSchemaToDisk() throws IOException {
        Path tempPath = schemaPath.resolveSibling(schemaPath.getFileName() + ".tmp");
        String json = GSON.toJson(schema);
        Files.writeString(tempPath, json);
        Files.move(tempPath, schemaPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private TableSchema loadSchemaFromDisk() throws IOException {
        String json = Files.readString(schemaPath);
        return GSON.fromJson(json, TableSchema.class);
    }

    private void loadTableState() throws IOException {
        primaryIndex.clear();
        long total = 0;
        long deleted = 0;
        String pkColName = schema.pkColumn().name();

        try (RandomAccessFile raf = new RandomAccessFile(dataPath.toFile(), "r")) {
            long currentOffset = 0;
            long fileLength = raf.length();

            while (currentOffset < fileLength) {
                long next = RecordCodec.nextOffset(raf, currentOffset);
                Map<String, Object> row = RecordCodec.read(raf, schema, currentOffset);

                total++;
                if (row == null) {
                    deleted++;
                } else {
                    Object pkValue = row.get(pkColName);
                    primaryIndex.put(pkValue, currentOffset);
                }
                currentOffset = next;
            }
        }

        this.totalRecords = total;
        this.tombstoneCount = deleted;
    }

    public String getShortSchema() {
        return schema.showShortSchema();
    }

    public String getFullSchema() {
        return schema.showSchema();
    }

    public TableSchema getSchema() {
        return schema;
    }


    public void insert(Object[] values) throws IOException {
        if (values == null) {
            throw new IllegalArgumentException("Передан null вместо массива значений");
        }
        ColumnDef[] columns = schema.getColumns();
        if (values.length != columns.length) {
            throw new IllegalArgumentException(
                    "Ожидалось значений: " + columns.length + ", но передано: " + values.length
            );
        }

        Map<String, Object> row = new LinkedHashMap<>(columns.length);
        for (int i = 0; i < columns.length; i++) {
            row.put(columns[i].name(), values[i]);
        }
        insert(row);
    }

    public void insert(Map<String, Object> row) throws IOException {
        if (row == null) {
            throw new IllegalArgumentException("Передана null-строка для вставки");
        }

        String pkColName = schema.pkColumn().name();
        Object pkValue = row.get(pkColName);
        if (pkValue == null) {
            throw new NullPointerException("Передана строка с null PK");
        }

        // Проверка уникальности PK за O(1) через индекс в памяти
        if (primaryIndex.containsKey(pkValue)) {
            throw new NonUniqueValueException("Дубликат первичного ключа: " + pkValue);
        }

        try (RandomAccessFile raf = new RandomAccessFile(dataPath.toFile(), "rw")) {
            long offset = RecordCodec.write(raf, schema, row, false);
            primaryIndex.put(pkValue, offset);
            totalRecords++;
        }
    }


    public Map<String, Object> selectByPk(Object pkValue) throws IOException {
        if (pkValue == null) {
            throw new IllegalArgumentException("PK не может быть null");
        }

        Long offset = primaryIndex.get(pkValue);
        if (offset == null) {
            return null;
        }

        try (RandomAccessFile raf = new RandomAccessFile(dataPath.toFile(), "r")) {
            return RecordCodec.read(raf, schema, offset);
        }
    }

    public List<Object[]> getAllRows() throws IOException {
        ColumnDef[] cols = schema.getColumns();
        List<Object[]> rows = new ArrayList<>();

        try (RandomAccessFile raf = new RandomAccessFile(dataPath.toFile(), "r")) {
            long currentOffset = 0;
            long fileLength = raf.length();

            while (currentOffset < fileLength) {
                long next = RecordCodec.nextOffset(raf, currentOffset);
                Map<String, Object> rowMap = RecordCodec.read(raf, schema, currentOffset);
                currentOffset = next;

                if (rowMap == null) {
                    continue;
                }

                Object[] rowValues = new Object[cols.length];
                for (int i = 0; i < cols.length; i++) {
                    rowValues[i] = rowMap.get(cols[i].name());
                }
                rows.add(rowValues);
            }
        }
        return rows;
    }

    public Map<String, Object[]> selectItems(String[] findingCols,
                                             String[] conditionCols,
                                             Predicate<RowView> condition) throws IOException {
        validateColumnsExist(findingCols);
        if (conditionCols != null) {
            validateColumnsExist(conditionCols);
        }

        Map<String, List<Object>> tempColumns = new LinkedHashMap<>();
        for (String colName : findingCols) {
            tempColumns.put(colName, new ArrayList<>());
        }

        String[] safeCondCols = (conditionCols != null) ? conditionCols : new String[0];
        ColumnType[] condTypes = resolveConditionTypes(safeCondCols);

        try (RandomAccessFile raf = new RandomAccessFile(dataPath.toFile(), "r")) {
            long currentOffset = 0;
            long fileLength = raf.length();

            while (currentOffset < fileLength) {
                long next = RecordCodec.nextOffset(raf, currentOffset);
                Map<String, Object> row = RecordCodec.read(raf, schema, currentOffset);
                currentOffset = next;

                if (row == null) {
                    continue;
                }

                Object[] conditionArgs = extractConditionArgs(row, safeCondCols);
                RowView view = new RowView(conditionArgs, condTypes, safeCondCols);

                if (condition == null || condition.test(view)) {
                    for (String findCol : findingCols) {
                        tempColumns.get(findCol).add(row.get(findCol));
                    }
                }
            }
        }

        Map<String, Object[]> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<Object>> entry : tempColumns.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toArray());
        }
        return result;
    }

    public int updateItems(Map<String, Object> newValues,
                           String[] conditionCols,
                           Predicate<RowView> condition) throws IOException {
        if (newValues == null || newValues.isEmpty()) {
            throw new IllegalArgumentException("Не переданы поля для обновления");
        }

        validateColumnsExist(newValues.keySet().toArray(new String[0]));
        if (conditionCols != null) {
            validateColumnsExist(conditionCols);
        }

        String[] safeCondCols = (conditionCols != null) ? conditionCols : new String[0];
        ColumnType[] condTypes = resolveConditionTypes(safeCondCols);
        String pkColName = schema.pkColumn().name();
        int updatedCount = 0;

        try (RandomAccessFile raf = new RandomAccessFile(dataPath.toFile(), "rw")) {
            long currentOffset = 0;
            long initialFileLength = raf.length();

            while (currentOffset < initialFileLength) {
                long next = RecordCodec.nextOffset(raf, currentOffset);
                Map<String, Object> row = RecordCodec.read(raf, schema, currentOffset);

                if (row != null) {
                    Object[] conditionArgs = extractConditionArgs(row, safeCondCols);
                    RowView view = new RowView(conditionArgs, condTypes, safeCondCols);

                    if (condition == null || condition.test(view)) {
                        Object oldPk = row.get(pkColName);
                        RecordCodec.markDeleted(raf, currentOffset);
                        primaryIndex.remove(oldPk);
                        tombstoneCount++;

                        row.putAll(newValues);
                        Object newPk = row.get(pkColName);
                        if (newPk == null) {
                            throw new NullPointerException("Нельзя обновить PK на null");
                        }
                        if (primaryIndex.containsKey(newPk)) {
                            throw new NonUniqueValueException("Дубликат первичного ключа при обновлении: " + newPk);
                        }

                        long newOffset = RecordCodec.write(raf, schema, row, false);
                        primaryIndex.put(newPk, newOffset);
                        totalRecords++;
                        updatedCount++;
                    }
                }
                currentOffset = next;
            }
        }

        if (shouldCompact()) {
            compact();
        }

        return updatedCount;
    }


    public int deleteItems(String[] conditionCols,
                           Predicate<RowView> condition) throws IOException {
        if (conditionCols != null) {
            validateColumnsExist(conditionCols);
        }

        String[] safeCondCols = (conditionCols != null) ? conditionCols : new String[0];
        ColumnType[] condTypes = resolveConditionTypes(safeCondCols);
        String pkColName = schema.pkColumn().name();
        int deletedCount = 0;

        try (RandomAccessFile raf = new RandomAccessFile(dataPath.toFile(), "rw")) {
            long currentOffset = 0;
            long fileLength = raf.length();

            while (currentOffset < fileLength) {
                long next = RecordCodec.nextOffset(raf, currentOffset);
                Map<String, Object> row = RecordCodec.read(raf, schema, currentOffset);

                if (row != null) {
                    Object[] conditionArgs = extractConditionArgs(row, safeCondCols);
                    RowView view = new RowView(conditionArgs, condTypes, safeCondCols);

                    if (condition == null || condition.test(view)) {
                        RecordCodec.markDeleted(raf, currentOffset);
                        primaryIndex.remove(row.get(pkColName));
                        tombstoneCount++;
                        deletedCount++;
                    }
                }
                currentOffset = next;
            }
        }

        if (shouldCompact()) {
            compact();
        }

        return deletedCount;
    }


    public void compact() throws IOException {
        Map<Object, Long> updatedIndex = RecordCodec.compactWithIndex(dataPath, schema);
        primaryIndex.clear();
        primaryIndex.putAll(updatedIndex);
        this.totalRecords = primaryIndex.size();
        this.tombstoneCount = 0;
    }

    private boolean shouldCompact() {
        if (totalRecords < MIN_RECORDS_FOR_COMPACT) {
            return false;
        }
        return ((double) tombstoneCount / totalRecords) >= TOMBSTONE_RATIO_THRESHOLD;
    }

    private Object[] extractConditionArgs(Map<String, Object> row, String[] conditionCols) {
        Object[] args = new Object[conditionCols.length];
        for (int i = 0; i < conditionCols.length; i++) {
            args[i] = row.get(conditionCols[i]);
        }
        return args;
    }

    private ColumnType[] resolveConditionTypes(String[] conditionCols) {
        ColumnType[] condTypes = new ColumnType[conditionCols.length];
        for (int i = 0; i < conditionCols.length; i++) {
            condTypes[i] = findColumnDef(conditionCols[i]).type();
        }
        return condTypes;
    }

    private ColumnDef findColumnDef(String colName) {
        for (ColumnDef col : schema.getColumns()) {
            if (col.name().equals(colName)) {
                return col;
            }
        }
        throw new UnknownColumnName(colName);
    }

    private void validateColumnsExist(String[] columnNames) {
        if (columnNames == null) {
            throw new IllegalArgumentException("Массив столбцов не может быть null");
        }
        for (String name : columnNames) {
            findColumnDef(name);
        }
    }
}
