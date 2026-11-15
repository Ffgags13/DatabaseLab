package main.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import main.core.domainExceptions.*;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Predicate;
/**
 * Представляет отдельную таблицу базы данных, управляющую хранением записей на диске
 * и индексами в оперативной памяти.
 * <p>
 * Схема таблицы сохраняется в JSON-файле ({@code .schema.json}), а бинарные записи —
 * в файле данных ({@code .dat}). Для быстрого поиска поддерживаются первичный индекс
 * ({@code PK -> offset}) и вторичные индексы ({@code значение -> множество PK}).
 */
public class Table {
    private final Path dataPath;
    private final Path schemaPath;
    private final TableSchema schema;

    private final Map<Object, Long> primaryIndex = new HashMap<>();
    private final Map<String, Map<Object, Set<Object>>> secondaryIndexes = new HashMap<>();

    public static final String SCHEMA_SUFFIX = ".schema.json";
    public static final String DATA_SUFFIX = ".dat";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final int MIN_RECORDS_FOR_COMPACT = 100;
    private static final double TOMBSTONE_RATIO_THRESHOLD = 0.3;
    private long totalRecords = 0;
    private long tombstoneCount = 0;
    /**
     * Создаёт новую таблицу в указанной директории, сохраняет её схему на диск
     * и инициализирует пустой файл данных.
     *
     * @param dbDirectory директория базы данных
     * @param schema      схема создаваемой таблицы
     * @throws IOException при ошибке создания директории или файлов таблицы
     */
    public Table(Path dbDirectory, TableSchema schema) throws IOException {
        Files.createDirectories(dbDirectory);
        String tableName = schema.getTableName();
        this.dataPath = dbDirectory.resolve(tableName + DATA_SUFFIX);
        this.schemaPath = dbDirectory.resolve(tableName + SCHEMA_SUFFIX);
        this.schema = schema;

        for (String indexedCol : schema.getIndexedColumns()) {
            secondaryIndexes.put(indexedCol, new HashMap<>());
        }

        saveSchemaToDisk();
        if (Files.notExists(this.dataPath)) {
            Files.createFile(this.dataPath);
        }
    }
    /**
     * Загружает существующую таблицу с диска по её имени и восстанавливает индексы в памяти.
     *
     * @param dbDirectory директория базы данных
     * @param tableName   имя загружаемой таблицы
     * @throws IOException           при ошибке чтения схемы или файла данных
     * @throws IllegalStateException если файл схемы или данных таблицы отсутствует на диске
     */
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
        secondaryIndexes.clear();
        for (String indexedCol : schema.getIndexedColumns()) {
            secondaryIndexes.put(indexedCol, new HashMap<>());
        }

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
                    addToSecondaryIndexes(row, pkValue);
                }
                currentOffset = next;
            }
        }
        this.totalRecords = total;
        this.tombstoneCount = deleted;
    }
    /**
     * Строит вторичный индекс по указанному столбцу, сканируя существующие записи таблицы,
     * и сохраняет обновлённый список индексов в файл схемы.
     *
     * @param colName имя индексируемого столбца
     * @throws IOException                при ошибке чтения данных или записи схемы
     * @throws UnknownColumnName          если столбец отсутствует в схеме таблицы
     * @throws IndexAlreadyExistsException если столбец является PK или индекс по нему уже существует
     */
    public void createIndex(String colName) throws IOException {
        ColumnDef colDef = findColumnDef(colName);
        if (colDef.equals(schema.pkColumn()) || secondaryIndexes.containsKey(colName)) {
            throw new IndexAlreadyExistsException(colName);
        }

        Map<Object, Set<Object>> indexMap = new HashMap<>();
        String pkColName = schema.pkColumn().name();

        try (RandomAccessFile raf = new RandomAccessFile(dataPath.toFile(), "r")) {
            long currentOffset = 0;
            long fileLength = raf.length();

            while (currentOffset < fileLength) {
                long next = RecordCodec.nextOffset(raf, currentOffset);
                Map<String, Object> row = RecordCodec.read(raf, schema, currentOffset);
                currentOffset = next;

                if (row == null) continue;

                Object fieldVal = row.get(colName);
                Object pkVal = row.get(pkColName);
                if (fieldVal != null) {
                    indexMap.computeIfAbsent(fieldVal, k -> new HashSet<>()).add(pkVal);
                }
            }
        }

        secondaryIndexes.put(colName, indexMap);
        schema.addIndexedColumn(colName);
        saveSchemaToDisk();
    }

    public String getShortSchema() { return schema.showShortSchema(); }
    public String getFullSchema() { return schema.showSchema(); }
    public TableSchema getSchema() { return schema; }
    /**
     * Вставляет новую строку, переданную в виде массива значений в порядке столбцов схемы.
     *
     * @param values массив значений полей
     * @throws IOException              при ошибке записи на диск
     * @throws IllegalArgumentException если передан {@code null}
     * @throws AmountValuesException    если длина массива не совпадает с числом столбцов схемы
     */
    public void insert(Object[] values) throws IOException {
        if (values == null) {
            throw new IllegalArgumentException("Передан null вместо массива значений");
        }
        ColumnDef[] columns = schema.getColumns();
        if (values.length != columns.length) {
            throw new AmountValuesException(columns.length);
        }

        Map<String, Object> row = new LinkedHashMap<>(columns.length);
        for (int i = 0; i < columns.length; i++) {
            row.put(columns[i].name(), values[i]);
        }
        insert(row);
    }
    /**
     * Вставляет новую строку в таблицу, записывая её на диск и обновляя индексы.
     *
     * @param row словарь значений в формате {@code имя_столбца -> значение}
     * @throws IOException              при ошибке записи на диск
     * @throws IllegalArgumentException если передан {@code null}
     * @throws NullValueException       если значение первичного ключа равно {@code null}
     * @throws NonUniqueValueException  если запись с таким первичным ключом уже существует
     */
    public void insert(Map<String, Object> row) throws IOException {
        if (row == null) {
            throw new IllegalArgumentException("Передан null вместо словаря значений");
        }
        validateColumnsExist(row.keySet().toArray(new String[0]));

        String pkColName = schema.pkColumn().name();
        Object pkValue = row.get(pkColName);
        if (pkValue == null) {
            throw new NullValueException(pkColName);
        }
        if (primaryIndex.containsKey(pkValue)) {
            throw new NonUniqueValueException(pkColName);
        }

        try (RandomAccessFile raf = new RandomAccessFile(dataPath.toFile(), "rw")) {
            long offset = RecordCodec.write(raf, schema, row, false);
            primaryIndex.put(pkValue, offset);
            addToSecondaryIndexes(row, pkValue);
            totalRecords++;
        }
    }
    /**
     * Выполняет прямой поиск записи по значению первичного ключа с использованием первичного индекса.
     *
     * @param pkValue значение первичного ключа
     * @return словарь значений найденной строки или {@code null}, если запись не найдена
     * @throws IOException              при ошибке чтения файла данных
     * @throws IllegalArgumentException если {@code pkValue} равен {@code null}
     */
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
    /**
     * Считывает все активные (неудалённые) строки таблицы в виде списка массивов значений.
     *
     * @return список массивов значений строк в порядке их хранения в файле
     * @throws IOException при ошибке чтения файла данных
     */
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

                if (rowMap == null) continue;

                Object[] rowValues = new Object[cols.length];
                for (int i = 0; i < cols.length; i++) {
                    rowValues[i] = rowMap.get(cols[i].name());
                }
                rows.add(rowValues);
            }
        }
        return rows;
    }
    /**
     * Выполняет выборку значений указанных столбцов для записей, удовлетворяющих предикату.
     * <p>
     * Если условие задано по одному столбцу, для которого существует первичный или вторичный
     * индекс, поиск автоматически выполняется по индексу без полного сканирования файла.
     *
     * @param findingCols   массив имён столбцов, значения которых должны попасть в результат
     * @param conditionCols массив имён столбцов, передаваемых в {@link RowView} для проверки условия
     * @param condition     условие отбора строк (если {@code null}, выбираются все активные записи)
     * @return словарь в формате {@code имя_столбца -> массив_значений}
     * @throws IOException       при ошибке чтения файла данных
     * @throws UnknownColumnName если хотя бы один из указанных столбцов отсутствует в схеме
     */
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
            if (condition != null && safeCondCols.length == 1) {
                String targetCol = safeCondCols[0];
                if (targetCol.equals(schema.pkColumn().name())) {
                    selectViaPrimaryIndex(raf, findingCols, targetCol, condTypes, condition, tempColumns);
                    return buildResultMap(tempColumns);
                } else if (secondaryIndexes.containsKey(targetCol)) {
                    selectViaSecondaryIndex(raf, findingCols, targetCol, condTypes, condition, tempColumns);
                    return buildResultMap(tempColumns);
                }
            }

            long currentOffset = 0;
            long fileLength = raf.length();

            while (currentOffset < fileLength) {
                long next = RecordCodec.nextOffset(raf, currentOffset);
                Map<String, Object> row = RecordCodec.read(raf, schema, currentOffset);
                currentOffset = next;

                if (row == null) continue;

                Object[] conditionArgs = extractConditionArgs(row, safeCondCols);
                RowView view = new RowView(conditionArgs, condTypes, safeCondCols);

                if (condition == null || condition.test(view)) {
                    for (String findCol : findingCols) {
                        tempColumns.get(findCol).add(row.get(findCol));
                    }
                }
            }
        }
        return buildResultMap(tempColumns);
    }

    private void selectViaPrimaryIndex(RandomAccessFile raf, String[] findingCols, String pkColName,
                                       ColumnType[] condTypes, Predicate<RowView> condition,
                                       Map<String, List<Object>> tempColumns) throws IOException {
        String[] colNames = new String[]{pkColName};
        for (Map.Entry<Object, Long> entry : primaryIndex.entrySet()) {
            RowView view = new RowView(new Object[]{entry.getKey()}, condTypes, colNames);
            if (condition.test(view)) {
                Map<String, Object> row = RecordCodec.read(raf, schema, entry.getValue());
                if (row != null) {
                    for (String findCol : findingCols) {
                        tempColumns.get(findCol).add(row.get(findCol));
                    }
                }
            }
        }
    }

    private void selectViaSecondaryIndex(RandomAccessFile raf, String[] findingCols, String indexedColName,
                                         ColumnType[] condTypes, Predicate<RowView> condition,
                                         Map<String, List<Object>> tempColumns) throws IOException {
        Map<Object, Set<Object>> indexMap = secondaryIndexes.get(indexedColName);
        String[] colNames = new String[]{indexedColName};

        for (Map.Entry<Object, Set<Object>> entry : indexMap.entrySet()) {
            RowView view = new RowView(new Object[]{entry.getKey()}, condTypes, colNames);
            if (condition.test(view)) {
                for (Object pk : entry.getValue()) {
                    Long offset = primaryIndex.get(pk);
                    if (offset != null) {
                        Map<String, Object> row = RecordCodec.read(raf, schema, offset);
                        if (row != null) {
                            for (String findCol : findingCols) {
                                tempColumns.get(findCol).add(row.get(findCol));
                            }
                        }
                    }
                }
            }
        }
    }

    private Map<String, Object[]> buildResultMap(Map<String, List<Object>> tempColumns) {
        Map<String, Object[]> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<Object>> entry : tempColumns.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toArray());
        }
        return result;
    }

    /**
     * Обновляет поля у записей, удовлетворяющих заданному условию.
     * <p>
     * Обновление выполняется по принципу append-only: старая версия записи помечается
     * удалённой ({@code tombstone = 1}), а новая дописывается в конец файла с обновлением
     * индексов и откатом при ошибке валидации. При накоплении удалённых записей запускает сжатие файла.
     *
     * @param newValues     словарь обновляемых столбцов и их новых значений
     * @param conditionCols массив имён столбцов, используемых в предикате {@code condition}
     * @param condition     условие отбора строк для обновления (если {@code null}, обновляются все записи)
     * @return количество обновлённых записей
     * @throws IOException              при ошибке ввода-вывода
     * @throws IllegalArgumentException если {@code newValues} пуст или равен {@code null}
     * @throws NullValueException       если новое значение PK или {@code NOT NULL} столбца равно {@code null}
     * @throws NonUniqueValueException  если новое значение уникального поля конфликтует с существующей записью
     */
    public long updateItems(Map<String, Object> newValues,
                            String[] conditionCols,
                            Predicate<RowView> condition) throws IOException {
        if (newValues == null || newValues.isEmpty()) {
            throw new IllegalArgumentException("Не переданы поля для обновления");
        }
        validateColumnsExist(newValues.keySet().toArray(new String[0]));
        if (conditionCols != null) {
            validateColumnsExist(conditionCols);
        }
        validateNewValuesBeforeUpdate(newValues);

        String[] safeCondCols = (conditionCols != null) ? conditionCols : new String[0];
        ColumnType[] condTypes = resolveConditionTypes(safeCondCols);
        String pkColName = schema.pkColumn().name();
        long updatedCount = 0;

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
                        Map<String, Object> updatedRow = new LinkedHashMap<>(row);
                        updatedRow.putAll(newValues);

                        Object newPk = updatedRow.get(pkColName);
                        if (newPk == null) throw new NullValueException(pkColName);
                        if (!Objects.equals(oldPk, newPk) && primaryIndex.containsKey(newPk)) {
                            throw new NonUniqueValueException(pkColName);
                        }

                        RecordCodec.markDeleted(raf, currentOffset);
                        primaryIndex.remove(oldPk);
                        removeFromSecondaryIndexes(row, oldPk);
                        tombstoneCount++;

                        try {
                            long newOffset = RecordCodec.write(raf, schema, updatedRow, false);
                            primaryIndex.put(newPk, newOffset);
                            addToSecondaryIndexes(updatedRow, newPk);
                            totalRecords++;
                            updatedCount++;
                        } catch (RuntimeException e) {
                            raf.seek(currentOffset + 4);
                            raf.writeByte(0);
                            primaryIndex.put(oldPk, currentOffset);
                            addToSecondaryIndexes(row, oldPk);
                            tombstoneCount--;
                            throw e;
                        }
                    }
                }
                currentOffset = next;
            }
        }

        if (shouldCompact()) compact();
        return updatedCount;
    }

    private void validateNewValuesBeforeUpdate(Map<String, Object> newValues) {
        for (Map.Entry<String, Object> entry : newValues.entrySet()) {
            String colName = entry.getKey();
            Object val = entry.getValue();
            ColumnDef colDef = findColumnDef(colName);

            if (val == null) {
                if (!colDef.nullable()) throw new NullValueException(colName);
                continue;
            }
            try {
                colDef.type().requireType(val);
            } catch (MaxLengthExceededException e) {
                throw new MaxLengthExceededException(colName);
            } catch (RuntimeException e) {
                throw new IncorrectType(colDef.type().name(), colName);
            }
        }
    }
    /**
     * Помечает как удалённые ({@code tombstone = 1}) все записи таблицы, удовлетворяющие условию,
     * и удаляет их из первичного и вторичных индексов.
     * <p>
     * При достижении порога удалённых записей автоматически запускает сжатие файла данных ({@link #compact()}).
     *
     * @param conditionCols массив имён столбцов, используемых в предикате {@code condition}
     * @param condition     условие отбора строк для удаления (если {@code null}, удаляются все записи)
     * @return количество удалённых записей
     * @throws IOException       при ошибке работы с файлом данных
     * @throws UnknownColumnName если хотя бы один из столбцов в {@code conditionCols} отсутствует в схеме
     */
    public long deleteItems(String[] conditionCols,
                            Predicate<RowView> condition) throws IOException {
        if (conditionCols != null) {
            validateColumnsExist(conditionCols);
        }

        String[] safeCondCols = (conditionCols != null) ? conditionCols : new String[0];
        ColumnType[] condTypes = resolveConditionTypes(safeCondCols);
        String pkColName = schema.pkColumn().name();
        long deletedCount = 0;

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
                        Object pkValue = row.get(pkColName);
                        RecordCodec.markDeleted(raf, currentOffset);
                        primaryIndex.remove(pkValue);
                        removeFromSecondaryIndexes(row, pkValue);
                        tombstoneCount++;
                        deletedCount++;
                    }
                }
                currentOffset = next;
            }
        }

        if (shouldCompact()) compact();
        return deletedCount;
    }
    /**
     * Выполняет сжатие (компактификацию) файла данных таблицы, удаляя записи с флагом {@code tombstone},
     * и полностью перестраивает первичный индекс в памяти по новым смещениям.
     *
     * @throws IOException при ошибке перезаписи или перемещения файла данных
     */
    public void compact() throws IOException {
        Map<Object, Long> updatedIndex = RecordCodec.compactWithIndex(dataPath, schema);
        primaryIndex.clear();
        primaryIndex.putAll(updatedIndex);
        this.totalRecords = primaryIndex.size();
        this.tombstoneCount = 0;
    }

    private boolean shouldCompact() {
        if (totalRecords < MIN_RECORDS_FOR_COMPACT) return false;
        return ((double) tombstoneCount / totalRecords) >= TOMBSTONE_RATIO_THRESHOLD;
    }

    private void addToSecondaryIndexes(Map<String, Object> row, Object pkVal) {
        for (Map.Entry<String, Map<Object, Set<Object>>> entry : secondaryIndexes.entrySet()) {
            Object val = row.get(entry.getKey());
            if (val != null) {
                entry.getValue().computeIfAbsent(val, k -> new HashSet<>()).add(pkVal);
            }
        }
    }

    private void removeFromSecondaryIndexes(Map<String, Object> row, Object pkVal) {
        for (Map.Entry<String, Map<Object, Set<Object>>> entry : secondaryIndexes.entrySet()) {
            Object val = row.get(entry.getKey());
            if (val != null) {
                Set<Object> pks = entry.getValue().get(val);
                if (pks != null) {
                    pks.remove(pkVal);
                    if (pks.isEmpty()) entry.getValue().remove(val);
                }
            }
        }
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
            if (col.name().equals(colName)) return col;
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
