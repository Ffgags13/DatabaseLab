package core;

import domainExceptions.DuplicateKeyException;
import domainExceptions.SchemaMismatchException;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Фасад над файлом {@code .dat}. Не хранит записи в памяти; результат
 * {@link #selectAll()} — это результат конкретного запроса, а не кэш.
 * <p>
 * Текущая стадия (фаза 4):
 * <ul>
 *   <li>{@link #create}/{@link #open}/{@link #close}</li>
 *   <li>{@link #insert(Map)} с проверкой PK через скан</li>
 *   <li>{@link #selectAll()} — полный скан</li>
 *   <li>{@link #getStats()} — статистика без декодирования payload</li>
 * </ul>
 * Индексы, DELETE, UPDATE, Condition — в следующих фазах.
 */
public final class Table implements AutoCloseable {

    private static final int HEADER_SIZE = 4 + 1;   // recordLen (int) + flags (byte)
    private static final int FLAG_TOMBSTONE = 1;

    private final Path dataFile;
    private final TableSchema schema;
    private final RandomAccessFile raf;
    private boolean closed = false;

    private Table(Path dataFile, TableSchema schema, RandomAccessFile raf) {
        this.dataFile = dataFile;
        this.schema = schema;
        this.raf = raf;
    }

    // ─────────────────────────── Lifecycle ───────────────────────────

    public static Table create(Path dataFile, TableSchema schema) throws IOException {
        Objects.requireNonNull(dataFile, "dataFile");
        Objects.requireNonNull(schema, "schema");

        Path parent = dataFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.exists(dataFile)) {
            throw new IOException("Файл уже существует: " + dataFile);
        }
        RandomAccessFile raf = new RandomAccessFile(dataFile.toFile(), "rw");
        return new Table(dataFile, schema, raf);
    }

    public static Table open(Path dataFile, TableSchema schema) throws IOException {
        Objects.requireNonNull(dataFile, "dataFile");
        Objects.requireNonNull(schema, "schema");

        if (!Files.exists(dataFile)) {
            throw new IOException("Файл не найден: " + dataFile);
        }
        if (!Files.isRegularFile(dataFile)) {
            throw new IOException("Не является обычным файлом: " + dataFile);
        }
        RandomAccessFile raf = new RandomAccessFile(dataFile.toFile(), "rw");
        return new Table(dataFile, schema, raf);
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        raf.close();
    }

    // ─────────────────────────── DML ───────────────────────────

    /**
     * Вставить одну запись.
     * <p>
     * Проверки до записи в файл:
     * <ol>
     *   <li>все ключи {@code row} есть в схеме;</li>
     *   <li>нет {@code null} в {@code nullable=false};</li>
     *   <li>PK ещё не встречается (скан — O(N); заменим индексом в фазе 7).</li>
     * </ol>
     *
     * @return offset начала записи (пригодится индексам позже)
     */
    public long insert(Map<String, Object> row) throws IOException {
        Objects.requireNonNull(row, "row");
        checkNotClosed();

        // 1. Неизвестные колонки
        for (String name : row.keySet()) {
            if (schema.findColumn(name).isEmpty()) {
                throw SchemaMismatchException.unknownColumn(name, getName());
            }
        }

        // 2. null в обязательных
        for (ColumnDef col : schema.getColumns()) {
            Object v = row.get(col.name());
            if (v == null && !col.nullable()) {
                throw SchemaMismatchException.nullInRequired(col.name(), getName());
            }
        }

        // 3. Уникальность PK (скан)
        String pkName = schema.pkColumn().name();
        Object pk = row.get(pkName);
        // PK по инварианту !nullable, значит null здесь уже невозможен,
        // но защищаемся от ручного обхода схемы:
        if (pk != null && pkExists(pkName, pk)) {
            throw new DuplicateKeyException("pk_" + pkName, pkName, pk);
        }

        return RecordCodec.write(raf, schema, row, false);
    }

    private boolean pkExists(String pkName, Object pk) throws IOException {
        long off = 0;
        long fileLen = raf.length();
        while (off + HEADER_SIZE <= fileLen) {
            raf.seek(off);
            int len = raf.readInt();
            if (off + HEADER_SIZE + len > fileLen) break; // оборванная последняя
            Map<String, Object> r = RecordCodec.read(raf, schema, off);
            if (r != null && Objects.equals(r.get(pkName), pk)) {
                return true;
            }
            off += HEADER_SIZE + len;
        }
        return false;
    }

    // ─────────────────────────── DQL ───────────────────────────

    /**
     * Прочитать все живые записи. Tombstone пропускаются.
     */
    public List<Map<String, Object>> selectAll() throws IOException {
        checkNotClosed();
        List<Map<String, Object>> result = new ArrayList<>();
        long off = 0;
        long fileLen = raf.length();
        while (off + HEADER_SIZE <= fileLen) {
            raf.seek(off);
            int len = raf.readInt();
            if (off + HEADER_SIZE + len > fileLen) break;
            Map<String, Object> row = RecordCodec.read(raf, schema, off);
            if (row != null) result.add(row);
            off += HEADER_SIZE + len;
        }
        return result;
    }

    // ─────────────────────────── Stats ───────────────────────────

    /**
     * Статистика таблицы. Читает только заголовки записей, payload не декодирует.
     */
    public TableStats getStats() throws IOException {
        checkNotClosed();
        long live = 0;
        long tomb = 0;
        long off = 0;
        long fileLen = raf.length();
        while (off + HEADER_SIZE <= fileLen) {
            raf.seek(off);
            int len = raf.readInt();
            if (off + HEADER_SIZE + len > fileLen) break;
            int flags = raf.readUnsignedByte();
            if ((flags & FLAG_TOMBSTONE) != 0) tomb++;
            else live++;
            off += HEADER_SIZE + len;
        }
        return new TableStats(live, tomb, fileLen);
    }

    // ─────────────────────────── Metadata ───────────────────────────

    public Path getDataFile() { return dataFile; }
    public TableSchema getSchema() { return schema; }
    public boolean isClosed() { return closed; }

    /** Имя таблицы без расширения: {@code cars.dat -> cars}. */
    public String getName() {
        String name = dataFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private void checkNotClosed() throws IOException {
        if (closed) throw new IOException("Таблица закрыта: " + dataFile);
    }
}