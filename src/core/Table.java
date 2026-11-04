package core;

import core.domainExceptions.ColumnNotFoundException;
import core.domainExceptions.DuplicateKeyException;
import core.domainExceptions.NullableKeyException;
import index.HashIndex;
import index.Index;
import index.IndexDefinition;
import index.IndexKind;
import index.IndexPersistence;
import index.TreeIndex;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Фасад над файлом {@code .dat} с поддержкой индексов.
 * <p>
 * <b>Что ускоряется:</b> проверка уникальности при {@code insert}/{@code update}
 * (O(1) вместо O(N)); {@code select(Condition)}/{@code delete(Condition)}/{@code update(Condition, …)}
 * при условии по индексированной колонке — через {@link #resolveOffsets}.
 * <p>
 * <b>Что не ускоряется:</b> {@code selectAll()}, {@code delete(Predicate)}, {@code update(Predicate, …)}
 * (Predicate непрозрачен — только скан), запросы по неиндексированной колонке.
 * <p>
 * PK-индекс (уникальный {@code HashIndex}) создаётся автоматически при
 * {@link #create}/{@link #open}.
 */
public final class Table implements AutoCloseable {

    private static final int HEADER_SIZE = 4 + 1;         // recordLen + flags
    private static final int FLAG_TOMBSTONE = 1;
    private static final double COMPACTION_THRESHOLD = 0.3;

    private final Path dataFile;
    private final TableSchema schema;
    private RandomAccessFile raf;
    private boolean closed = false;

    private final List<Index> indexes = new ArrayList<>();
    private final Map<String, Index> indexesByColumn = new HashMap<>();

    private Table(Path dataFile, TableSchema schema, RandomAccessFile raf) {
        this.dataFile = dataFile;
        this.schema = schema;
        this.raf = raf;
    }

    // ─────────────────────────── Lifecycle ───────────────────────────

    public static Table create(Path dataFile, TableSchema schema) throws IOException {
        return create(dataFile, schema, List.of());
    }

    public static Table create(Path dataFile, TableSchema schema,
                               List<IndexDefinition> extraIndexes) throws IOException {
        Objects.requireNonNull(dataFile);
        Objects.requireNonNull(schema);

        Path parent = dataFile.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        if (Files.exists(dataFile)) throw new IOException("Файл уже существует: " + dataFile);

        RandomAccessFile raf = new RandomAccessFile(dataFile.toFile(), "rw");
        Table t = new Table(dataFile, schema, raf);

        // PK-индекс обязателен
        t.addIndex(IndexDefinition.forPrimaryKey(schema), false);
        for (IndexDefinition def : extraIndexes) {
            if (def.column().equals(schema.pkColumn().name())) continue;
            t.addIndex(def, false);
        }
        t.flushAllIndexes();   // ← ДОБАВИТЬ: создать .idx-файлы сразу
        return t;
    }

    public static Table open(Path dataFile, TableSchema schema) throws IOException {
        return open(dataFile, schema, List.of());
    }

    public static Table open(Path dataFile, TableSchema schema,
                             List<IndexDefinition> indexDefs) throws IOException {
        Objects.requireNonNull(dataFile);
        Objects.requireNonNull(schema);
        Objects.requireNonNull(indexDefs);

        if (!Files.exists(dataFile)) throw new IOException("Файл не найден: " + dataFile);
        if (!Files.isRegularFile(dataFile)) throw new IOException("Не обычный файл: " + dataFile);

        RandomAccessFile raf = new RandomAccessFile(dataFile.toFile(), "rw");
        Table t = new Table(dataFile, schema, raf);

        t.addIndex(IndexDefinition.forPrimaryKey(schema), true);
        for (IndexDefinition def : indexDefs) {
            if (def.column().equals(schema.pkColumn().name())) continue;
            t.addIndex(def, true);
        }
        return t;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        flushAllIndexes();       // сначала сбросить индексы (нужен raf.length())
        closed = true;
        raf.close();
    }

    // ─────────────────────────── Index DDL ───────────────────────────

    /** CREATE INDEX. Идемпотентно (если индекс уже есть — исключение). */
    public void createIndex(String column, IndexKind kind, boolean unique) throws IOException {
        checkNotClosed();
        if (indexesByColumn.containsKey(column)) {
            throw new IllegalArgumentException("Индекс на колонке уже существует: " + column);
        }
        ColumnDef col;
        try { col = schema.getColumn(column); }
        catch (ColumnNotFoundException e) {
            throw new IllegalArgumentException("Колонка не найдена: " + column);
        }

        Index ix = newIndex(column, col.type(), kind, unique);
        rebuildSingle(ix);
        indexes.add(ix);
        indexesByColumn.put(column, ix);
        ix.flushToDisk(indexPath(column), raf.length());
    }

    /** DROP INDEX. PK-индекс удалить нельзя. */
    public void dropIndex(String column) throws IOException {
        checkNotClosed();
        if (column.equals(schema.pkColumn().name())) {
            throw new IllegalArgumentException("Нельзя удалить индекс PK: " + column);
        }
        Index ix = indexesByColumn.remove(column);
        if (ix == null) throw new IllegalArgumentException("Индекса нет: " + column);
        indexes.remove(ix);
        Files.deleteIfExists(indexPath(column));
    }

    public boolean hasIndexOn(String column) { return indexesByColumn.containsKey(column); }

    public Index findIndexOn(String column) { return indexesByColumn.get(column); }

    public List<Index> getIndexes() { return List.copyOf(indexes); }

    // ─────────────────────────── DML (Predicate — скан) ───────────────────────────

    public long insert(Map<String, Object> row) throws IOException {
        Objects.requireNonNull(row);
        checkNotClosed();

        // 1. Опечатки в именах колонок
        for (String name : row.keySet()) {
            try { schema.getColumn(name); }
            catch (ColumnNotFoundException e) {
                throw new IllegalArgumentException("Неизвестная колонка: " + name);
            }
        }

        // 2. PK не null
        String pkName = schema.pkColumn().name();
        if (row.get(pkName) == null) {
            throw new NullableKeyException("PK «" + pkName + "» не задан");
        }

        // 3. Уникальность через все unique-индексы (включая PK)
        for (Index ix : indexes) {
            if (!ix.isUnique()) continue;
            Object v = row.get(ix.column());
            if (v == null) continue;
            if (!ix.lookupEq(v).isEmpty()) {
                throw new DuplicateKeyException("idx_" + ix.column(), ix.column(), v);
            }
        }

        // 4. Запись + индексы
        long off = RecordCodec.write(raf, schema, row, false);
        for (Index ix : indexes) {
            ix.add(row.get(ix.column()), off);
        }
        return off;
    }

    public int delete(Predicate<Map<String, Object>> where) throws IOException {
        Objects.requireNonNull(where);
        checkNotClosed();
        int n = 0;
        for (long off : scanAllOffsets()) {
            Map<String, Object> row = RecordCodec.read(raf, schema, off);
            if (row != null && where.test(row)) {
                deleteAt(off, row);
                n++;
            }
        }
        return n;
    }

    public int update(Predicate<Map<String, Object>> where,
                      Map<String, Object> patch) throws IOException {
        Objects.requireNonNull(where);
        Objects.requireNonNull(patch);
        checkNotClosed();
        List<Long> offsets = new ArrayList<>();
        for (long off : scanAllOffsets()) {
            Map<String, Object> row = RecordCodec.read(raf, schema, off);
            if (row != null && where.test(row)) offsets.add(off);
        }
        return updateAt(offsets, patch);
    }

    // ─────────────────────────── DQL / DML (Condition — индексы) ───────────────────────────

    /** SELECT * WHERE … через индексы, если возможно. */
    public List<Map<String, Object>> select(Condition where) throws IOException {
        Objects.requireNonNull(where);
        checkNotClosed();
        List<Map<String, Object>> result = new ArrayList<>();
        for (long off : resolveOffsets(where)) {
            Map<String, Object> row = RecordCodec.read(raf, schema, off);
            if (row != null && matches(where, row)) result.add(row);
        }
        return result;
    }

    public int delete(Condition where) throws IOException {
        Objects.requireNonNull(where);
        checkNotClosed();
        int n = 0;
        for (long off : resolveOffsets(where)) {
            Map<String, Object> row = RecordCodec.read(raf, schema, off);
            if (row != null && matches(where, row)) {
                deleteAt(off, row);
                n++;
            }
        }
        return n;
    }

    public int update(Condition where, Map<String, Object> patch) throws IOException {
        Objects.requireNonNull(where);
        Objects.requireNonNull(patch);
        checkNotClosed();
        List<Long> offsets = new ArrayList<>();
        for (long off : resolveOffsets(where)) {
            Map<String, Object> row = RecordCodec.read(raf, schema, off);
            if (row != null && matches(where, row)) offsets.add(off);
        }
        return updateAt(offsets, patch);
    }

    /** COUNT(*) WHERE …; через индексы — быстрее сканa. */
    public long count(Condition where) throws IOException {
        Objects.requireNonNull(where);
        checkNotClosed();
        long n = 0;
        for (long off : resolveOffsets(where)) {
            Map<String, Object> row = RecordCodec.read(raf, schema, off);
            if (row != null && matches(where, row)) n++;
        }
        return n;
    }

    /** EXISTS(…); возвращает на первом совпадении. */
    public boolean exists(Condition where) throws IOException {
        Objects.requireNonNull(where);
        checkNotClosed();
        for (long off : resolveOffsets(where)) {
            Map<String, Object> row = RecordCodec.read(raf, schema, off);
            if (row != null && matches(where, row)) return true;
        }
        return false;
    }

    // ─────────────────────────── DQL (без условий) ───────────────────────────

    public List<Map<String, Object>> selectAll() throws IOException {
        checkNotClosed();
        List<Map<String, Object>> result = new ArrayList<>();
        for (long off : scanAllOffsets()) {
            Map<String, Object> row = RecordCodec.read(raf, schema, off);
            if (row != null) result.add(row);
        }
        return result;
    }

    public TableStats getStats() throws IOException {
        checkNotClosed();
        long live = 0, tomb = 0, off = 0;
        long fileLen = raf.length();
        while (off + HEADER_SIZE <= fileLen) {
            raf.seek(off);
            int len = raf.readInt();
            if (off + HEADER_SIZE + len > fileLen) break;
            int flags = raf.readUnsignedByte();
            if ((flags & FLAG_TOMBSTONE) != 0) tomb++; else live++;
            off += HEADER_SIZE + len;
        }
        return new TableStats(live, tomb, fileLen);
    }

    // ─────────────────────────── Compact ───────────────────────────

    public boolean needsCompaction() throws IOException {
        return getStats().tombstoneRatio() > COMPACTION_THRESHOLD;
    }

    public int compact() throws IOException {
        checkNotClosed();
        TableStats before = getStats();
        if (before.tombstoneRecords() == 0) return 0;

        Path tmp = dataFile.resolveSibling(dataFile.getFileName() + ".compact.tmp");
        Files.deleteIfExists(tmp);
        try (RandomAccessFile out = new RandomAccessFile(tmp.toFile(), "rw")) {
            long off = 0, fileLen = raf.length();
            while (off + HEADER_SIZE <= fileLen) {
                raf.seek(off);
                int len = raf.readInt();
                if (off + HEADER_SIZE + len > fileLen) break;
                Map<String, Object> row = RecordCodec.read(raf, schema, off);
                if (row != null) RecordCodec.write(out, schema, row, false);
                off += HEADER_SIZE + len;
            }
        }

        raf.close();
        try {
            try {
                Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING);
            }
            raf = new RandomAccessFile(dataFile.toFile(), "rw");

            // Offsets изменились — индексы перестраиваем с нуля
            for (Index ix : indexes) ix.clear();
            for (long off = 0, fileLen = raf.length(); off + HEADER_SIZE <= fileLen; ) {
                raf.seek(off);
                int len = raf.readInt();
                if (off + HEADER_SIZE + len > fileLen) break;
                Map<String, Object> row = RecordCodec.read(raf, schema, off);
                if (row != null) {
                    for (Index ix : indexes) ix.add(row.get(ix.column()), off);
                }
                off += HEADER_SIZE + len;
            }
            flushAllIndexes();
        } catch (IOException e) {
            closed = true;
            throw e;
        }
        return (int) before.tombstoneRecords();
    }

    // ─────────────────────────── Diagnostics ───────────────────────────

    /**
     * Проверяет, что каждая живая запись есть во всех индексах под своим ключом.
     * Обратное (что каждый offset индекса ведёт на живую запись) — не проверяется;
     * для лабораторки этого достаточно.
     */
    public boolean checkIndexConsistency() throws IOException {
        checkNotClosed();
        for (long off : scanAllOffsets()) {
            Map<String, Object> row = RecordCodec.read(raf, schema, off);
            if (row == null) continue;
            for (Index ix : indexes) {
                Object v = row.get(ix.column());
                if (v == null) continue;   // ← ДОБАВИТЬ: null не индексируется
                if (!ix.lookupEq(v).contains(off)) return false;
            }
        }
        return true;
    }
    // ─────────────────────────── Metadata ───────────────────────────

    public Path getDataFile() { return dataFile; }
    public TableSchema getSchema() { return schema; }
    public boolean isClosed() { return closed; }
    public String getName() { return schema.getTableName(); }

    // ─────────────────────────── Internals ───────────────────────────

    private void checkNotClosed() throws IOException {
        if (closed) throw new IOException("Таблица закрыта: " + dataFile);
    }

    private Index newIndex(String column, ColumnType type, IndexKind kind, boolean unique) {
        return switch (kind) {
            case HASH -> new HashIndex(column, type, unique);
            case TREE -> new TreeIndex(column, type, unique);
        };
    }

    private Path indexPath(String column) {
        return dataFile.getParent().resolve(schema.getTableName() + ".idx." + column);
    }

    /**
     * Регистрирует индекс: пробует загрузить с диска (если datSize сходится),
     * иначе перестраивает сканом. Для create — rebuild не нужен (файл пуст).
     */
    private void addIndex(IndexDefinition def, boolean loadOrRebuild) throws IOException {
        ColumnDef col;
        try { col = schema.getColumn(def.column()); }
        catch (ColumnNotFoundException e) {
            throw new IOException("Индекс на несуществующей колонке: " + def.column());
        }
        Index ix = newIndex(def.column(), col.type(), def.kind(), def.unique());

        if (loadOrRebuild) {
            Path idxFile = indexPath(def.column());
            boolean loaded = false;
            if (Files.exists(idxFile)) {
                try {
                    if (IndexPersistence.readStoredDatSize(idxFile) == raf.length()) {
                        ix.loadFromDisk(idxFile);
                        loaded = true;
                    }
                } catch (IOException ignored) {
                    // не загрузилось — перестроим ниже
                }
            }
            if (!loaded) {
                rebuildSingle(ix);
                ix.flushToDisk(idxFile, raf.length());
            }
        }
        indexes.add(ix);
        indexesByColumn.put(ix.column(), ix);
    }

    /** Перестроить один индекс сканом .dat. */
    private void rebuildSingle(Index ix) throws IOException {
        ix.clear();
        for (long off : scanAllOffsets()) {
            Map<String, Object> row = RecordCodec.read(raf, schema, off);
            if (row != null) ix.add(row.get(ix.column()), off);
        }
    }

    private void flushAllIndexes() throws IOException {
        for (Index ix : indexes) {
            ix.flushToDisk(indexPath(ix.column()), raf.length());
        }
    }

    /** Offsets всех живых записей, сканом от начала до конца. */
    private List<Long> scanAllOffsets() throws IOException {
        List<Long> out = new ArrayList<>();
        long off = 0, fileLen = raf.length();
        while (off + HEADER_SIZE <= fileLen) {
            raf.seek(off);
            int len = raf.readInt();
            if (off + HEADER_SIZE + len > fileLen) break;
            raf.seek(off + 4);
            int flags = raf.readUnsignedByte();
            if ((flags & FLAG_TOMBSTONE) == 0) out.add(off);
            off += HEADER_SIZE + len;
        }
        return out;
    }

    /** Единая точка «где искать offsets»: индексы, если повезёт, иначе полный скан. */
    private List<Long> resolveOffsets(Condition where) throws IOException {
        List<Long> idx = tryIndexed(where);
        return idx != null ? idx : scanAllOffsets();
    }

    /** Возвращает offsets из индекса или {@code null}, если индекс не применим. */
    private List<Long> tryIndexed(Condition c) {
        if (c instanceof Condition.Eq eq) {
            Index ix = indexesByColumn.get(eq.column());
            return ix == null ? null : ix.lookupEq(eq.value());
        }
        if (c instanceof Condition.Gt gt) return rangeOrNull(gt.column(), gt.value(),
                Index::lookupGt);
        if (c instanceof Condition.Ge ge) return rangeOrNull(ge.column(), ge.value(),
                Index::lookupGe);
        if (c instanceof Condition.Lt lt) return rangeOrNull(lt.column(), lt.value(),
                Index::lookupLt);
        if (c instanceof Condition.Le le) return rangeOrNull(le.column(), le.value(),
                Index::lookupLe);

        if (c instanceof Condition.And and) {
            List<Long> left = tryIndexed(and.left());
            List<Long> right = tryIndexed(and.right());
            if (left != null && right != null) {
                Set<Long> s = new HashSet<>(left);
                s.retainAll(right);
                return new ArrayList<>(s);
            }
            if (left != null) return left;   // правая ветка — пост-фильтр в matches
            if (right != null) return right;
            return null;
        }
        if (c instanceof Condition.Or or) {
            List<Long> left = tryIndexed(or.left());
            List<Long> right = tryIndexed(or.right());
            if (left != null && right != null) {
                Set<Long> s = new HashSet<>(left);
                s.addAll(right);
                return new ArrayList<>(s);
            }
            return null;   // OR нельзя ускорить, если хотя бы одна ветка не индексирована
        }
        return null;
    }

    /** Для range-запросов: возвращает offsets из TREE-индекса или null. */
    private List<Long> rangeOrNull(String column, Object value,
                                   RangeLookup op) {
        Index ix = indexesByColumn.get(column);
        if (ix == null) return null;
        try { return op.apply(ix, value); }
        catch (UnsupportedOperationException e) { return null; }   // HASH
    }

    @FunctionalInterface
    private interface RangeLookup {
        List<Long> apply(Index ix, Object value);
    }

    /** Проверка одной строки на условие (пост-фильтр после resolveOffsets). */
    private boolean matches(Condition c, Map<String, Object> row) {
        if (c instanceof Condition.Eq eq) return Objects.equals(row.get(eq.column()), eq.value());
        if (c instanceof Condition.Gt gt) return cmp(row.get(gt.column()), gt.value()) > 0;
        if (c instanceof Condition.Ge ge) return cmp(row.get(ge.column()), ge.value()) >= 0;
        if (c instanceof Condition.Lt lt) return cmp(row.get(lt.column()), lt.value()) < 0;
        if (c instanceof Condition.Le le) return cmp(row.get(le.column()), le.value()) <= 0;
        if (c instanceof Condition.And and) return matches(and.left(), row) && matches(and.right(), row);
        if (c instanceof Condition.Or or)  return matches(or.left(), row) || matches(or.right(), row);
        throw new IllegalStateException("Unknown condition: " + c);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int cmp(Object a, Object b) {
        if (a == null || b == null) return a == b ? 0 : (a == null ? -1 : 1);
        return ((Comparable) a).compareTo(b);
    }

    private void deleteAt(long off, Map<String, Object> row) throws IOException {
        for (Index ix : indexes) ix.remove(row.get(ix.column()), off);
        raf.seek(off + 4);
        raf.writeByte(FLAG_TOMBSTONE);
    }

    private int updateAt(List<Long> offsets, Map<String, Object> patch) throws IOException {
        // 0. Ключи patch существуют в схеме
        for (String name : patch.keySet()) {
            try { schema.getColumn(name); }
            catch (ColumnNotFoundException e) {
                throw new IllegalArgumentException("Неизвестная колонка в patch: " + name);
            }
        }
        String pkName = schema.pkColumn().name();
        int updated = 0;

        for (long oldOff : offsets) {
            Map<String, Object> old = RecordCodec.read(raf, schema, oldOff);
            if (old == null) continue;

            Map<String, Object> merged = new LinkedHashMap<>(old);
            merged.putAll(patch);

            // null в обязательных
            for (ColumnDef col : schema.getColumns()) {
                if (merged.get(col.name()) == null && !col.nullable()) {
                    throw new IllegalArgumentException("null в не nullable столбце " + col.name());
                }
            }

            // Уникальность для изменившихся значений
            for (Index ix : indexes) {
                if (!ix.isUnique()) continue;
                Object oldVal = old.get(ix.column());
                Object newVal = merged.get(ix.column());
                if (newVal == null) continue;
                if (Objects.equals(oldVal, newVal)) continue;
                if (!ix.lookupEq(newVal).isEmpty()) {
                    throw new DuplicateKeyException("idx_" + ix.column(), ix.column(), newVal);
                }
            }

            // Кодируем ДО изменения файла: если упадёт — старая цела
            byte[] payload = RecordCodec.encodePayLoad(schema, merged);

            // Новый offset + запись
            long newOff = raf.length();
            raf.seek(newOff);
            raf.writeInt(payload.length);
            raf.writeByte(0);
            raf.write(payload);

            // Tombstone старой
            raf.seek(oldOff + 4);
            raf.writeByte(FLAG_TOMBSTONE);

            // Индексы: убрать старую, добавить новую
            for (Index ix : indexes) {
                ix.remove(old.get(ix.column()), oldOff);
                ix.add(merged.get(ix.column()), newOff);
            }
            updated++;
        }
        return updated;
    }
}