package core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Кодек записей: {@code Map} ⇄ {@code byte[]}, плюс I/O в {@link RandomAccessFile}.
 * <p>
 * Формат записи: {@code [recordLen:int][flags:byte][payload]}.
 * Бит 0 в {@code flags} = tombstone. Формат payload — по порядку колонок схемы.
 */
public final class RecordCodec {

    private static final int DEFAULT_CONTAINER_SIZE = 64;

    private RecordCodec() {}

    // ─────────────────────────── require* ───────────────────────────

    private static int requireInt(String colName, Object item) {
        return switch (item) {
            case Integer i -> i;
            case Long l -> Math.toIntExact(l);
            default -> throw new IllegalArgumentException(
                    "Колонка " + colName + " ожидает INT, получено: "
                            + item.getClass().getSimpleName());
        };
    }

    private static double requireDouble(String colName, Object item) {
        return switch (item) {
            case Double d -> d;
            case Float f -> f;
            case Byte b -> b;
            case Integer i -> i;
            case Long l -> l;
            default -> throw new IllegalArgumentException(
                    "Колонка " + colName + " ожидает DOUBLE, получено: "
                            + item.getClass().getSimpleName());
        };
    }

    private static boolean requireBoolean(String colName, Object item) {
        if (item instanceof Boolean b) return b;
        throw new IllegalArgumentException(
                "Колонка " + colName + " ожидает Boolean, получено: "
                        + item.getClass().getSimpleName());
    }

    private static String requireString(String colName, Object item) {
        if (item instanceof String s) return s;
        throw new IllegalArgumentException(
                "Колонка " + colName + " ожидает String, получено: "
                        + item.getClass().getSimpleName());
    }

    private static LocalDate requireDate(String colName, Object item) {
        if (item instanceof LocalDate d) return d;
        throw new IllegalArgumentException(
                "Колонка " + colName + " ожидает Date, получено: "
                        + item.getClass().getSimpleName());
    }

    // ─────────────────────────── encode / decode ───────────────────────────

    public static byte[] encodePayLoad(TableSchema schema, Map<String, Object> row) {
        // PK обязателен
        if (row.get(schema.pkColumn().name()) == null) {
            throw new NullPointerException("Передана строка с нулевым PK");
        }
        try {
            ByteArrayOutputStream container = new ByteArrayOutputStream(DEFAULT_CONTAINER_SIZE);
            DataOutputStream out = new DataOutputStream(container);

            for (ColumnDef col : schema.getColumns()) {
                Object item = row.get(col.name());
                if (item == null) {
                    if (!col.nullable())
                        throw new IllegalArgumentException(
                                "null в не nullable столбце " + col.name());
                    out.writeByte(1);
                    continue;
                }
                out.writeByte(0);

                switch (col.type()) {
                    case INT -> out.writeInt(requireInt(col.name(), item));
                    case DOUBLE -> out.writeDouble(requireDouble(col.name(), item));
                    case BOOLEAN -> out.writeBoolean(requireBoolean(col.name(), item));
                    case STRING -> {
                        byte[] data = requireString(col.name(), item)
                                .getBytes(StandardCharsets.UTF_8);
                        if (data.length > Short.MAX_VALUE)
                            throw new IllegalArgumentException(
                                    "Строка слишком длинная для колонки " + col.name()
                                            + ": " + data.length + " байт (max "
                                            + Short.MAX_VALUE + ")");
                        out.writeShort(data.length);
                        out.write(data);
                    }
                    case DATE -> out.writeLong(requireDate(col.name(), item).toEpochDay());
                }
            }
            out.flush();
            return container.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Map<String, Object> decodePayLoad(TableSchema schema, byte[] container) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(container));
            Map<String, Object> row = new LinkedHashMap<>();

            for (ColumnDef col : schema.getColumns()) {
                int nullFlag = in.readUnsignedByte();
                if (nullFlag == 1) {
                    row.put(col.name(), null);
                    continue;
                }
                Object item = switch (col.type()) {
                    case INT -> in.readInt();
                    case DOUBLE -> in.readDouble();
                    case BOOLEAN -> in.readBoolean();
                    case DATE -> LocalDate.ofEpochDay(in.readLong());
                    case STRING -> {
                        int length = in.readUnsignedShort();
                        byte[] data = new byte[length];
                        in.readFully(data);
                        yield new String(data, StandardCharsets.UTF_8);
                    }
                };
                row.put(col.name(), item);
            }
            if (in.available() != 0)
                throw new IllegalStateException(
                        "Остались лишние байты в container: " + in.available()
                                + " (схема и файл разошлись)");
            return row;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ─────────────────────────── file I/O ───────────────────────────

    /** Записать запись (с кодированием) в конец файла. Возвращает offset. */
    public static long write(RandomAccessFile file, TableSchema schema,
                             Map<String, Object> row, boolean tombstone) throws IOException {
        return writeEncoded(file, encodePayLoad(schema, row), tombstone);
    }

    /**
     * Записать уже закодированный payload в конец файла. Возвращает offset.
     * <p>
     * Используется при {@code UPDATE}: сначала кодируем (валидация), потом
     * tombstone старой, потом пишем новую — без риска потерять данные.
     */
    public static long writeEncoded(RandomAccessFile file, byte[] payload,
                                    boolean tombstone) throws IOException {
        long offset = file.length();
        file.seek(offset);
        file.writeInt(payload.length);
        file.writeByte(tombstone ? 1 : 0);
        file.write(payload);
        return offset;
    }

    /** Прочитать запись по offset. {@code null} — если tombstone. */
    public static Map<String, Object> read(RandomAccessFile file,
                                           TableSchema schema, long offset) throws IOException {
        file.seek(offset);
        int length = file.readInt();
        int flags = file.readByte();
        if ((flags & 1) != 0) {
            file.skipBytes(length);
            return null;
        }
        byte[] container = new byte[length];
        file.readFully(container);
        return decodePayLoad(schema, container);
    }

    /** Пометить запись удалённой (tombstone). Байт flags становится 1. */
    public static void markDeleted(RandomAccessFile file, long offset) throws IOException {
        file.seek(offset + 4);
        file.writeByte(1);
    }

    /** Offset начала следующей записи. */
    public static long nextOffset(RandomAccessFile file, long offset) throws IOException {
        file.seek(offset);
        int length = file.readInt();
        return offset + 4 + 1 + length;
    }
}