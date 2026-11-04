package core;

import domainExceptions.SchemaMismatchException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Кодек записей: превращает {@code Map<String,Object>} в {@code byte[]} и
 * обратно, а также пишет/читает запись из {@link RandomAccessFile}.
 * <p>
 * Формат записи в файле:
 * <pre>
 *   [ int32 recordLen ][ byte flags ][ payload (recordLen байт) ]
 * </pre>
 * {@code flags} — битовая маска; младший бит ({@code 1}) = tombstone
 * (запись удалена). Остальные биты зарезервированы.
 * <p>
 * Формат payload (по колонкам в порядке схемы):
 * <pre>
 *   для каждой колонки:
 *     [ byte nullFlag ]   0 = значение есть, 1 = null
 *     [ значение ]        если nullFlag == 0
 *   INT     -> int32
 *   DOUBLE  -> double64
 *   BOOLEAN -> byte (0/1)
 *   STRING  -> uint16 length + UTF-8 bytes
 *   DATE    -> int64 (epochDay, дни с 1970-01-01)
 * </pre>
 * <p>
 * Класс утилитарный: инстансы не создаются.
 */
public final class RecordCodec {

    /** Начальная ёмкость {@link ByteArrayOutputStream} под одну запись. */
    private static final int DEFAULT_CONTAINER_SIZE = 64;

    /** Бит 0 в {@code flags} — запись помечена как удалённая. */
    public static final int FLAG_TOMBSTONE = 1;

    /** Размер заголовка записи: {@code recordLen (4)} + {@code flags (1)}. */
    public static final int HEADER_SIZE = 4 + 1;

    private RecordCodec() {
        // утилитарный класс — не инстанцируется
    }

    // ─────────────────────────── require* ───────────────────────────

    private static int requireInt(String colName, Object item) {
        return switch (item) {
            case Integer i -> i;
            case Long l -> Math.toIntExact(l);
            case Short s -> s;
            case Byte b -> b;
            default -> throw SchemaMismatchException.typeMismatch(
                    colName, ColumnType.INT, item);
        };
    }

    private static double requireDouble(String colName, Object item) {
        return switch (item) {
            case Double d -> d;
            case Float f -> f;
            case Long l -> l;
            case Integer i -> i;
            case Short s -> s;
            case Byte b -> b;
            default -> throw SchemaMismatchException.typeMismatch(
                    colName, ColumnType.DOUBLE, item);
        };
    }

    private static boolean requireBoolean(String colName, Object item) {
        if (item instanceof Boolean b) {
            return b;
        }
        throw SchemaMismatchException.typeMismatch(colName, ColumnType.BOOLEAN, item);
    }

    private static String requireString(String colName, Object item) {
        if (item instanceof String s) {
            return s;
        }
        throw SchemaMismatchException.typeMismatch(colName, ColumnType.STRING, item);
    }

    /**
     * Канонический тип для {@code DATE} — {@link LocalDate}. Если пришёл
     * {@link java.util.Date}, он конвертируется через системную зону
     * (дата, а не момент времени).
     */
    private static LocalDate requireDate(String colName, Object item) {
        if (item instanceof LocalDate d) {
            return d;
        }
        if (item instanceof java.util.Date d) {
            return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }
        if (item instanceof java.sql.Date d) {   // java.sql.Date extends java.util.Date
            return d.toLocalDate();
        }
        throw SchemaMismatchException.typeMismatch(colName, ColumnType.DATE, item);
    }

    // ─────────────────────────── encode / decode ───────────────────────────

    /**
     * Превратить одну запись в {@code byte[]} (payload без заголовка).
     * <p>
     * Проверяет: PK присутствует, {@code null} только в {@code nullable=true},
     * типы значений совпадают с типами колонок.
     *
     * @throws SchemaMismatchException неизвестная колонка, {@code null} в
     *                                 обязательной, тип не совпадает, PK не задан
     */
    public static byte[] encodePayload(TableSchema schema, Map<String, Object> row) {
        // PK обязателен
        String pkName = schema.pkColumn().name();
        if (row.get(pkName) == null) {
            throw SchemaMismatchException.missingRequired(pkName);
        }

        try {
            ByteArrayOutputStream container = new ByteArrayOutputStream(DEFAULT_CONTAINER_SIZE);
            DataOutputStream out = new DataOutputStream(container);

            for (ColumnDef col : schema.getColumns()) {
                Object item = row.get(col.name());

                if (item == null) {
                    if (!col.nullable()) {
                        throw SchemaMismatchException.nullInRequired(col.name());
                    }
                    out.writeByte(1);     // null-флаг
                    continue;
                }
                out.writeByte(0);         // значение есть

                switch (col.type()) {
                    case INT     -> out.writeInt(requireInt(col.name(), item));
                    case DOUBLE  -> out.writeDouble(requireDouble(col.name(), item));
                    case BOOLEAN -> out.writeBoolean(requireBoolean(col.name(), item));
                    case STRING  -> {
                        byte[] data = requireString(col.name(), item)
                                .getBytes(StandardCharsets.UTF_8);
                        if (data.length > Short.MAX_VALUE) {
                            throw new SchemaMismatchException(
                                    SchemaMismatchException.Kind.TYPE_MISMATCH,
                                    col.name(),
                                    "STRING <= " + Short.MAX_VALUE + " байт",
                                    data.length + " байт",
                                    null,
                                    "Строка слишком длинная для колонки «" + col.name()
                                            + "»: " + data.length + " байт (max "
                                            + Short.MAX_VALUE + ")");
                        }
                        out.writeShort(data.length);
                        out.write(data);
                    }
                    case DATE    -> out.writeLong(requireDate(col.name(), item).toEpochDay());
                }
            }

            out.flush();
            return container.toByteArray();
        } catch (IOException e) {
            // ByteArrayOutputStream не бросает IOException по факту — оборачиваем
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Восстановить запись из payload (без заголовка).
     * <p>
     * Возвращает {@link LinkedHashMap} — порядок колонок как в схеме.
     *
     * @throws IllegalStateException если после чтения всех колонок остались
     *                               лишние байты (схема и файл разошлись)
     */
    public static Map<String, Object> decodePayload(TableSchema schema, byte[] container) {
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
                    case INT     -> in.readInt();
                    case DOUBLE  -> in.readDouble();
                    case BOOLEAN -> in.readBoolean();
                    case DATE    -> LocalDate.ofEpochDay(in.readLong());
                    case STRING  -> {
                        int length = in.readUnsignedShort();
                        byte[] data = new byte[length];
                        in.readFully(data);
                        yield new String(data, StandardCharsets.UTF_8);
                    }
                };
                row.put(col.name(), item);
            }

            if (in.available() != 0) {
                throw new IllegalStateException(
                        "Остались лишние байты в payload: " + in.available()
                                + " (схема и файл разошлись?)");
            }
            return row;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ─────────────────────────── file I/O ───────────────────────────

    /**
     * Записать одну запись в конец файла.
     * <p>
     * Формат: {@code [recordLen:int][flags:byte][payload:recordLen]}.
     *
     * @param file      открытый файл (курсор не важен — метод сам делает {@code seek})
     * @param schema    схема таблицы
     * @param row       данные записи
     * @param tombstone {@code true} — пометить как удалённую (для {@code UPDATE}/{@code DELETE})
     * @return offset, с которого начинается запись
     */
    public static long write(RandomAccessFile file, TableSchema schema,
                             Map<String, Object> row, boolean tombstone) throws IOException {
        byte[] container = encodePayload(schema, row);
        long offset = file.length();
        file.seek(offset);
        file.writeInt(container.length);
        file.writeByte(tombstone ? FLAG_TOMBSTONE : 0);
        file.write(container);
        return offset;
    }

    /**
     * Прочитать запись по offset.
     *
     * @return {@code Map} полей, либо {@code null}, если запись — tombstone
     */
    public static Map<String, Object> read(RandomAccessFile file,
                                           TableSchema schema, long offset) throws IOException {
        file.seek(offset);
        int length = file.readInt();
        int flags = file.readUnsignedByte();

        if ((flags & FLAG_TOMBSTONE) != 0) {
            file.skipBytes(length);
            return null;
        }

        byte[] container = new byte[length];
        file.readFully(container);   // гарантирует полное заполнение, иначе EOFException
        return decodePayload(schema, container);
    }

    /**
     * Пометить запись удалённой (tombstone), не перемещая остальные данные.
     * <p>
     * Пишет {@code 1} в байт флагов (сразу после {@code recordLen}).
     * После этого {@link #read} на этом offset вернёт {@code null}.
     */
    public static void markDeleted(RandomAccessFile file, long offset) throws IOException {
        file.seek(offset + 4);              // пропустить recordLen (4 байта)
        file.writeByte(FLAG_TOMBSTONE);     // выставить бит 0
    }

    /**
     * Offset начала следующей записи (или {@code offset + HEADER_SIZE + recordLen}
     * для текущей). Не читает payload — только длину.
     */
    public static long nextOffset(RandomAccessFile file, long offset) throws IOException {
        file.seek(offset);
        int length = file.readInt();
        return offset + HEADER_SIZE + length;
    }
}