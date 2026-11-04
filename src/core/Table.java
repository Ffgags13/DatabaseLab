package core;

import core.domainExceptions.ColumnNotFoundException;
import core.domainExceptions.NullableKeyException;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.function.Predicate;

public final class Table implements AutoCloseable {

    private static final int HEADER_SIZE = 4 + 1;   // совпадает с RecordCodec.nextOffset
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
        if (parent != null) Files.createDirectories(parent);
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

    public long insert(Map<String, Object> row) throws IOException {
        Objects.requireNonNull(row, "row");
        checkNotClosed();

        // 1. Опечатки в именах колонок — RecordCodec их не заметит
        for (String name : row.keySet()) {
            if(!schema.hasColumn(name)){
                throw new IllegalArgumentException("Неизвестная колонка: " + name);
            }
        }

        // 2. PK не null
        String pkName = schema.pkColumn().name();
        Object pk = row.get(pkName);
        if (pk == null) {
            throw new NullableKeyException("PK «" + pkName + "» не задан");
        }

        // 3. Уникальность PK (скан)
        if (pkExists(pkName, pk)) {
            throw new IllegalArgumentException(
                    "Нарушено уникальное ограничение PK: " + pkName + "=" + pk);
        }

        return RecordCodec.write(raf, schema, row, false);
    }

    private boolean pkExists(String pkName, Object pk) throws IOException {
        long off = 0;
        long fileLen = raf.length();
        while (off + HEADER_SIZE <= fileLen) {
            raf.seek(off);
            int len = raf.readInt();
            if (off + HEADER_SIZE + len > fileLen) break;   // оборванная последняя
            Map<String, Object> r = RecordCodec.read(raf, schema, off);
            if (r != null && Objects.equals(r.get(pkName), pk)) return true;
            off += HEADER_SIZE + len;
        }
        return false;
    }

    // ─────────────────────────── DQL ───────────────────────────

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

    public TableStats getStats() throws IOException {
        checkNotClosed();
        long live = 0, tomb = 0, off = 0;
        long fileLen = raf.length();
        while (off + HEADER_SIZE <= fileLen) {
            raf.seek(off);
            int len = raf.readInt();
            if (off + HEADER_SIZE + len > fileLen) break;
            int flags = raf.readUnsignedByte();   // unsigned — чтобы будущие биты не уехали в знак
            if ((flags & FLAG_TOMBSTONE) != 0) tomb++;
            else live++;
            off += HEADER_SIZE + len;
        }
        return new TableStats(live, tomb, fileLen);
    }

    // ─────────────────────────── DELETE ───────────────────────────

    /**
     * Удалить все живые записи, удовлетворяющие условию.
     * <p>
     * Реализация: скан файла, для совпавших — {@link RecordCodec#markDeleted}.
     * Байты не стираются, место освобождается только при {@code compact()} (фаза 8).
     *
     * @return сколько записей реально помечено
     */
    public int delete(Predicate<Map<String, Object>> where) throws IOException {
        Objects.requireNonNull(where, "where");
        checkNotClosed();

        int deleted = 0;
        long off = 0;
        long fileLen = raf.length();
        while (off + HEADER_SIZE <= fileLen) {
            raf.seek(off);
            int len = raf.readInt();
            if (off + HEADER_SIZE + len > fileLen) break;      // оборванная последняя

            Map<String, Object> row = RecordCodec.read(raf, schema, off);
            if (row != null && where.test(row)) {
                RecordCodec.markDeleted(raf, off);
                deleted++;
            }
            off += HEADER_SIZE + len;
        }
        return deleted;
    }

    // ─────────────────────────── UPDATE ───────────────────────────

    /**
     * Обновить все живые записи, удовлетворяющие условию.
     * <p>
     * Реализация: старая запись помечается tombstone, новая (старая + patch)
     * дописывается в конец. Offsets старых записей не двигаются — индексы
     * (фаза 7) останутся валидными; на новой записи будет новый offset.
     * <p>
     * Порядок шагов внутри — важен:
     * <ol>
     *   <li>скан: собрать offsets совпавших записей;</li>
     *   <li>для каждого: merged = old + patch;</li>
     *   <li>валидация: null в обязательных, PK уникален (если сменился);</li>
     *   <li>{@code encodePayLoad} — типовая валидация до любых изменений файла;</li>
     *   <li>только теперь: {@code markDeleted(old)} + {@code writeEncoded(new)}.</li>
     * </ol>
     * Если что-то из 3–4 бросило — файл не тронут для этой записи.
     *
     * @return сколько записей реально обновлено
     */
    public int update(Predicate<Map<String, Object>> where,
                      Map<String, Object> patch) throws IOException {
        Objects.requireNonNull(where, "where");
        Objects.requireNonNull(patch, "patch");
        checkNotClosed();

        // 0. Ключи patch существуют в схеме
        for (String name : patch.keySet()) {
            try {
                schema.getColumn(name);
            } catch (ColumnNotFoundException e) {
                throw new IllegalArgumentException("Неизвестная колонка в patch: " + name);
            }
        }

        // 1. Скан: собрать offsets (не меняем файл во время скана)
        List<Long> toUpdate = new ArrayList<>();
        long off = 0;
        long fileLen = raf.length();
        while (off + HEADER_SIZE <= fileLen) {
            raf.seek(off);
            int len = raf.readInt();
            if (off + HEADER_SIZE + len > fileLen) break;

            Map<String, Object> row = RecordCodec.read(raf, schema, off);
            if (row != null && where.test(row)) {
                toUpdate.add(off);
            }
            off += HEADER_SIZE + len;
        }

        // 2. Обработка
        String pkName = schema.pkColumn().name();
        int updated = 0;
        for (long oldOff : toUpdate) {
            Map<String, Object> old = RecordCodec.read(raf, schema, oldOff);
            if (old == null) continue;                          // уже tombstone (не должно, но защита)

            Map<String, Object> merged = new LinkedHashMap<>(old);
            merged.putAll(patch);

            // 3a. null в обязательных
            for (ColumnDef col : schema.getColumns()) {
                if (merged.get(col.name()) == null && !col.nullable()) {
                    throw new IllegalArgumentException(
                            "null в не nullable столбце " + col.name());
                }
            }

            // 3b. Если PK меняется — новый PK не должен быть занят
            Object oldPk = old.get(pkName);
            Object newPk = merged.get(pkName);
            if (!Objects.equals(oldPk, newPk) && pkExists(pkName, newPk)) {
                throw new IllegalArgumentException(
                        "Нарушено уникальное ограничение PK: " + pkName + "=" + newPk);
            }

            // 4. Кодируем ДО tombstone: если encode упадёт — старая цела
            byte[] payload = RecordCodec.encodePayLoad(schema, merged);

            // 5. Только теперь меняем файл
            RecordCodec.markDeleted(raf, oldOff);
            RecordCodec.writeEncoded(raf, payload, false);
            updated++;
        }
        return updated;
    }

    // ─────────────────────────── Metadata ───────────────────────────

    public Path getDataFile() { return dataFile; }
    public TableSchema getSchema() { return schema; }
    public boolean isClosed() { return closed; }

    /** Имя таблицы — из схемы, а не из имени файла. */
    public String getName() { return schema.getTableName(); }

    private void checkNotClosed() throws IOException {
        if (closed) throw new IOException("Таблица закрыта: " + dataFile);
    }
}
