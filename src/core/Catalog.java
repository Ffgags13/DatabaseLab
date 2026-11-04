package core;

import core.domainExceptions.ColumnNotFoundException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Каталог таблиц. Хранит открытые {@link Table} и их {@link TableSchema},
 * сериализует метаданные в простой текстовый файл.
 * <p>
 * Формат файла (построчный):
 * <pre>
 * TABLE cars
 * COLUMN id INT false
 * COLUMN brand STRING false
 * COLUMN price DOUBLE true
 * TABLE users
 * COLUMN ...
 * </pre>
 * <p>
 * Имя файла данных всегда {@code dataDir/<tableName>.dat}. Первый
 * {@code COLUMN} таблицы — первичный ключ.
 * <p>
 * Использование:
 * <pre>
 * try (Catalog catalog = new Catalog(Path.of("db/catalog.txt"), Path.of("db"))) {
 *     if (!catalog.hasTable("cars")) {
 *         catalog.createTable("cars", new ColumnDef[]{
 *             new ColumnDef("id",    ColumnType.INT,    false),
 *             new ColumnDef("brand", ColumnType.STRING, false)
 *         });
 *     }
 *     Table cars = catalog.getTable("cars");
 *     cars.insert(Map.of("id", 1, "brand", "Toyota"));
 * }
 * </pre>
 */
public final class Catalog implements AutoCloseable {

    private final Path catalogFile;
    private final Path dataDir;

    /** Схемы — для сохранения каталога. Отдельно от Table, чтобы не зависеть от открытых файлов. */
    private final Map<String, TableSchema> schemas = new LinkedHashMap<>();

    /** Открытые таблицы. */
    private final Map<String, Table> tables = new LinkedHashMap<>();

    private boolean closed = false;

    /**
     * Открыть каталог. Если файл каталога существует — загружает таблицы.
     * Если нет — стартует пустым (создаст при первом {@link #createTable}).
     *
     * @param catalogFile путь к файлу каталога (например, {@code db/catalog.txt})
     * @param dataDir     папка, где лежат {@code .dat}-файлы (например, {@code db})
     */
    public Catalog(Path catalogFile, Path dataDir) throws IOException {
        this.catalogFile = Objects.requireNonNull(catalogFile, "catalogFile");
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir");

        Files.createDirectories(dataDir);
        if (Files.exists(catalogFile)) {
            load();
        }
    }

    // ─────────────────────────── DDL ───────────────────────────

    /**
     * Создать новую таблицу и её {@code .dat}-файл.
     *
     * @param tableName имя таблицы (также имя файла: {@code <tableName>.dat})
     * @param columns   колонки, первый — PK
     * @throws IllegalArgumentException если таблица уже существует
     * @throws IOException              если не удалось создать файл
     */
    public Table createTable(String tableName, ColumnDef[] columns) throws IOException {
        checkNotClosed();
        if (hasTable(tableName)) {
            throw new IllegalArgumentException("Таблица уже существует: " + tableName);
        }
        TableSchema schema = new TableSchema(tableName, columns);
        Path dataFile = dataFileFor(tableName);

        Table table = Table.create(dataFile, schema);
        schemas.put(tableName, schema);
        tables.put(tableName, table);
        save();
        return table;
    }

    /**
     * Удалить таблицу: закрыть файл, удалить {@code .dat}, убрать из каталога.
     *
     * @throws IllegalArgumentException если таблицы нет
     */
    public void dropTable(String tableName) throws IOException {
        checkNotClosed();
        Table table = tables.get(tableName);
        if (table == null) {
            throw new IllegalArgumentException("Таблица не найдена: " + tableName);
        }
        table.close();
        tables.remove(tableName);
        schemas.remove(tableName);
        Files.deleteIfExists(dataFileFor(tableName));
        save();
    }

    // ─────────────────────────── Lookup ───────────────────────────

    /**
     * Получить открытую таблицу по имени.
     *
     * @throws IllegalArgumentException если таблицы нет
     */
    public Table getTable(String tableName) {
        Table table = tables.get(tableName);
        if (table == null) {
            throw new IllegalArgumentException("Таблица не найдена: " + tableName);
        }
        return table;
    }

    /** Есть ли таблица с таким именем. */
    public boolean hasTable(String tableName) {
        return tables.containsKey(tableName);
    }

    /** Имена всех таблиц в порядке добавления. */
    public List<String> listTableNames() {
        return new ArrayList<>(tables.keySet());
    }

    /** Схема таблицы без получения самой таблицы. */
    public TableSchema getSchema(String tableName) {
        TableSchema schema = schemas.get(tableName);
        if (schema == null) {
            throw new IllegalArgumentException("Таблица не найдена: " + tableName);
        }
        return schema;
    }

    // ─────────────────────────── Lifecycle ───────────────────────────

    /**
     * Закрыть все открытые таблицы. Идемпотентно.
     */
    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;

        IOException firstError = null;
        for (Table table : tables.values()) {
            try {
                table.close();
            } catch (IOException e) {
                if (firstError == null) firstError = e;
            }
        }
        tables.clear();

        if (firstError != null) throw firstError;
    }

    // ─────────────────────────── Internals ───────────────────────────

    private Path dataFileFor(String tableName) {
        return dataDir.resolve(tableName + ".dat");
    }

    private void checkNotClosed() throws IOException {
        if (closed) throw new IOException("Каталог закрыт: " + catalogFile);
    }

    /** Загрузить каталог: прочитать схемы и открыть все {@code .dat}. */
    private void load() throws IOException {
        List<String> lines = Files.readAllLines(catalogFile, StandardCharsets.UTF_8);

        String currentTable = null;
        List<ColumnDef> currentColumns = new ArrayList<>();

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            String[] parts = line.split("\\s+");
            switch (parts[0]) {
                case "TABLE" -> {
                    if (currentTable != null) {
                        finishTable(currentTable, currentColumns);
                    }
                    if (parts.length < 2) {
                        throw new IOException("Неверная строка TABLE: " + line);
                    }
                    currentTable = parts[1];
                    currentColumns = new ArrayList<>();
                }
                case "COLUMN" -> {
                    if (currentTable == null) {
                        throw new IOException("COLUMN без TABLE: " + line);
                    }
                    if (parts.length < 4) {
                        throw new IOException("Неверная строка COLUMN: " + line);
                    }
                    String colName = parts[1];
                    ColumnType type = ColumnType.valueOf(parts[2]);
                    boolean nullable = Boolean.parseBoolean(parts[3]);
                    currentColumns.add(new ColumnDef(colName, type, nullable));
                }
                default -> throw new IOException("Неизвестная строка в каталоге: " + line);
            }
        }
        if (currentTable != null) {
            finishTable(currentTable, currentColumns);
        }
    }

    private void finishTable(String tableName, List<ColumnDef> columns) throws IOException {
        if (columns.isEmpty()) {
            throw new IOException("У таблицы " + tableName + " нет колонок");
        }
        TableSchema schema = new TableSchema(tableName, columns.toArray(new ColumnDef[0]));
        Path file = dataFileFor(tableName);
        if (!Files.exists(file)) {
            throw new IOException("Файл таблицы отсутствует: " + file
                    + " (каталог ссылается на несуществующий .dat)");
        }
        Table table = Table.open(file, schema);
        schemas.put(tableName, schema);
        tables.put(tableName, table);
    }

    /** Сохранить каталог атомарно: {@code catalog.txt.tmp} -> {@code catalog.txt}. */
    private void save() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Каталог таблиц. Формат:\n");
        sb.append("#   TABLE <имя>\n");
        sb.append("#   COLUMN <имя> <тип> <nullable>\n");
        sb.append('\n');

        for (Map.Entry<String, TableSchema> e : schemas.entrySet()) {
            sb.append("TABLE ").append(e.getKey()).append('\n');
            for (ColumnDef col : e.getValue().getColumns()) {
                sb.append("COLUMN ")
                        .append(col.name()).append(' ')
                        .append(col.type().name()).append(' ')
                        .append(col.nullable())
                        .append('\n');
            }
            sb.append('\n');
        }

        Path tmp = catalogFile.resolveSibling(catalogFile.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, catalogFile,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }
}
