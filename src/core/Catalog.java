package core;

import index.IndexDefinition;
import index.IndexKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Каталог таблиц: хранит открытые {@link Table}, их схемы и определения индексов;
 * сериализует метаданные в {@code catalog.txt}.
 * <p>
 * Формат:
 * <pre>
 * TABLE cars
 * COLUMN id INT false
 * COLUMN brand STRING false
 * COLUMN price DOUBLE true
 * INDEX brand HASH false
 * INDEX price TREE false
 * </pre>
 * PK-индекс не хранится (создаётся автоматически в {@link Table}).
 */
public final class Catalog implements AutoCloseable {

    private final Path catalogFile;
    private final Path dataDir;

    private final Map<String, TableSchema> schemas = new LinkedHashMap<>();
    private final Map<String, List<IndexDefinition>> indexDefs = new LinkedHashMap<>();
    private final Map<String, Table> tables = new LinkedHashMap<>();

    private boolean closed = false;

    public Catalog(Path catalogFile, Path dataDir) throws IOException {
        this.catalogFile = Objects.requireNonNull(catalogFile, "catalogFile");
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir");

        Files.createDirectories(dataDir);
        if (Files.exists(catalogFile)) {
            load();
        }
    }

    // ─────────────────────────── DDL таблиц ───────────────────────────

    public Table createTable(String tableName, ColumnDef[] columns) throws IOException {
        return createTable(tableName, columns, List.of());
    }

    public Table createTable(String tableName, ColumnDef[] columns,
                             List<IndexDefinition> extraIndexes) throws IOException {
        checkNotClosed();
        if (hasTable(tableName)) {
            throw new IllegalArgumentException("Таблица уже существует: " + tableName);
        }
        TableSchema schema = new TableSchema(tableName, columns);
        Path dataFile = dataFileFor(tableName);

        Table table = Table.create(dataFile, schema, extraIndexes);
        schemas.put(tableName, schema);
        indexDefs.put(tableName, new ArrayList<>(extraIndexes));
        tables.put(tableName, table);
        save();
        return table;
    }

    public void dropTable(String tableName) throws IOException {
        checkNotClosed();
        Table table = tables.get(tableName);
        if (table == null) {
            throw new IllegalArgumentException("Таблица не найдена: " + tableName);
        }
        table.close();
        tables.remove(tableName);
        schemas.remove(tableName);
        indexDefs.remove(tableName);

        Files.deleteIfExists(dataFileFor(tableName));
        // Удаляем все .idx.<column> для этой таблицы (включая PK)
        try (DirectoryStream<Path> ds =
                     Files.newDirectoryStream(dataDir, tableName + ".idx.*")) {
            for (Path p : ds) Files.deleteIfExists(p);
        }
        save();
    }

    // ─────────────────────────── DDL индексов ───────────────────────────

    /** CREATE INDEX: делегирует в {@link Table#createIndex}, обновляет каталог. */
    public void createIndex(String tableName, String column, IndexKind kind, boolean unique)
            throws IOException {
        checkNotClosed();
        Table table = getTable(tableName);
        table.createIndex(column, kind, unique);
        indexDefs.computeIfAbsent(tableName, k -> new ArrayList<>())
                .add(IndexDefinition.of(column, kind, unique));
        save();
    }

    /** DROP INDEX: делегирует в {@link Table#dropIndex}, обновляет каталог. */
    public void dropIndex(String tableName, String column) throws IOException {
        checkNotClosed();
        Table table = getTable(tableName);
        table.dropIndex(column);
        List<IndexDefinition> list = indexDefs.get(tableName);
        if (list != null) {
            list.removeIf(d -> d.column().equals(column));
        }
        save();
    }

    // ─────────────────────────── Lookup ───────────────────────────

    public Table getTable(String tableName) {
        Table table = tables.get(tableName);
        if (table == null) {
            throw new IllegalArgumentException("Таблица не найдена: " + tableName);
        }
        return table;
    }

    public boolean hasTable(String tableName) { return tables.containsKey(tableName); }

    public List<String> listTableNames() { return new ArrayList<>(tables.keySet()); }

    public TableSchema getSchema(String tableName) {
        TableSchema schema = schemas.get(tableName);
        if (schema == null) {
            throw new IllegalArgumentException("Таблица не найдена: " + tableName);
        }
        return schema;
    }

    public List<IndexDefinition> getIndexDefinitions(String tableName) {
        return List.copyOf(indexDefs.getOrDefault(tableName, List.of()));
    }

    // ─────────────────────────── Lifecycle ───────────────────────────

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException firstError = null;
        for (Table table : tables.values()) {
            try { table.close(); }
            catch (IOException e) { if (firstError == null) firstError = e; }
        }
        tables.clear();
        if (firstError != null) throw firstError;
    }

    // ─────────────────────────── Internals ───────────────────────────

    private Path dataFileFor(String tableName) { return dataDir.resolve(tableName + ".dat"); }

    private void checkNotClosed() throws IOException {
        if (closed) throw new IOException("Каталог закрыт: " + catalogFile);
    }

    private void load() throws IOException {
        List<String> lines = Files.readAllLines(catalogFile, StandardCharsets.UTF_8);

        String curTable = null;
        List<ColumnDef> curCols = new ArrayList<>();
        List<IndexDefinition> curIdx = new ArrayList<>();

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            String[] parts = line.split("\\s+");
            switch (parts[0]) {
                case "TABLE" -> {
                    if (curTable != null) finishTable(curTable, curCols, curIdx);
                    if (parts.length < 2) throw new IOException("Неверно TABLE: " + line);
                    curTable = parts[1];
                    curCols = new ArrayList<>();
                    curIdx = new ArrayList<>();
                }
                case "COLUMN" -> {
                    if (curTable == null) throw new IOException("COLUMN без TABLE: " + line);
                    if (parts.length < 4) throw new IOException("Неверно COLUMN: " + line);
                    curCols.add(new ColumnDef(parts[1],
                            ColumnType.valueOf(parts[2]),
                            Boolean.parseBoolean(parts[3])));
                }
                case "INDEX" -> {
                    if (curTable == null) throw new IOException("INDEX без TABLE: " + line);
                    if (parts.length < 4) throw new IOException("Неверно INDEX: " + line);
                    curIdx.add(IndexDefinition.of(parts[1],
                            IndexKind.valueOf(parts[2]),
                            Boolean.parseBoolean(parts[3])));
                }
                default -> throw new IOException("Неизвестная строка в каталоге: " + line);
            }
        }
        if (curTable != null) finishTable(curTable, curCols, curIdx);
    }

    private void finishTable(String tableName, List<ColumnDef> cols, List<IndexDefinition> idx)
            throws IOException {
        if (cols.isEmpty()) throw new IOException("У таблицы " + tableName + " нет колонок");

        TableSchema schema = new TableSchema(tableName, cols.toArray(new ColumnDef[0]));
        Path file = dataFileFor(tableName);
        if (!Files.exists(file)) {
            throw new IOException("Файл таблицы отсутствует: " + file);
        }
        Table table = Table.open(file, schema, idx);
        schemas.put(tableName, schema);
        indexDefs.put(tableName, new ArrayList<>(idx));
        tables.put(tableName, table);
    }

    private void save() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Каталог таблиц\n\n");

        for (Map.Entry<String, TableSchema> e : schemas.entrySet()) {
            String name = e.getKey();
            sb.append("TABLE ").append(name).append('\n');
            for (ColumnDef col : e.getValue().getColumns()) {
                sb.append("COLUMN ").append(col.name()).append(' ')
                        .append(col.type().name()).append(' ')
                        .append(col.nullable()).append('\n');
            }
            for (IndexDefinition d : indexDefs.getOrDefault(name, List.of())) {
                sb.append("INDEX ").append(d.column()).append(' ')
                        .append(d.kind().name()).append(' ')
                        .append(d.unique()).append('\n');
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