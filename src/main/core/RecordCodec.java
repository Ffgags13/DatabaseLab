package main.core;

import main.core.domainExceptions.*;

import java.io.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Утилитный класс для бинарной сериализации записей и низкоуровневой работы с файлом данных таблицы.
 * <p>
 * Формат хранения каждой записи в файле:
 * <ul>
 *   <li>{@code int length} (4 байта) — размер полезной нагрузки в байтах;</li>
 *   <li>{@code byte tombstone} (1 байт) — флаг удаления записи ({@code 1} — удалена, {@code 0} — активна);</li>
 *   <li>{@code byte[] data} — сериализованные значения столбцов с префиксом {@code null}-флага перед каждым полем.</li>
 * </ul>
 */
public class RecordCodec {
    private static final int DEFAULT_CONTAINER_SIZE = 64;

    /**
     * Сериализует строку таблицы в массив байтов согласно схеме.
     *
     * @param schema схема таблицы
     * @param row    словарь значений в формате {@code имя_столбца -> значение}
     * @return массив байтов с закодированными данными строки
     * @throws NullValueException         если отсутствует PK или передан {@code null} для {@code NOT NULL} столбца
     * @throws IncorrectType              если тип значения не соответствует типу столбца в схеме
     * @throws MaxLengthExceededException если длина строкового значения превышает допустимый лимит
     * @throws UnknownColumnName          если в {@code row} присутствуют столбцы, отсутствующие в схеме
     */
    public static byte[] encodePayLoad(TableSchema schema, Map<String, Object> row) {
        // Проверяем PK
        if (row.get(schema.pkColumn().name()) == null) {
            throw new NullValueException(schema.pkColumn().name());
        }

        // Проверяем, что все ключи row присутствуют в схеме
        for (String key : row.keySet()) {
            boolean found = false;
            for (ColumnDef col : schema.getColumns()) {
                if (col.name().equals(key)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new UnknownColumnName(key);
            }
        }

        try {
            ByteArrayOutputStream container = new ByteArrayOutputStream(DEFAULT_CONTAINER_SIZE);
            DataOutputStream outContainer = new DataOutputStream(container);

            for (ColumnDef col : schema.getColumns()) {
                Object item = row.get(col.name());
                if (item == null) {
                    if (!col.nullable()) {
                        throw new NullValueException(col.name());
                    }
                    outContainer.writeByte(1);
                    continue;
                }
                outContainer.writeByte(0);
                try {
                    col.type().binaryWrite(outContainer, item);
                } catch (IllegalArgumentException e) {
                    throw new IncorrectType(col.type().name(), col.name());
                } catch (MaxLengthExceededException e) {
                    throw new MaxLengthExceededException(col.name());
                }
            }
            outContainer.flush();
            return container.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Восстанавливает словарь значений строки из массива байтов.
     *
     * @param schema    схема таблицы
     * @param container бинарная полезная нагрузка записи
     * @return восстановленная строка в формате {@code имя_столбца -> значение}
     * @throws IllegalStateException если после чтения всех столбцов в буфере остались лишние байты
     */
    public static Map<String, Object> decodePayLoad(TableSchema schema, byte[] container) {
        try {
            DataInputStream inContainer = new DataInputStream(new ByteArrayInputStream(container));
            Map<String, Object> row = new LinkedHashMap<>();

            for (ColumnDef col : schema.getColumns()) {
                int nullFlag = inContainer.readUnsignedByte();
                if (nullFlag == 1) {
                    row.put(col.name(), null);
                    continue;
                }
                Object item = col.type().binaryRead(inContainer);
                row.put(col.name(), item);
            }
            if (inContainer.available() != 0) {
                throw new IllegalStateException(
                        "Остались лишние байты в container: " +
                                inContainer.available() +
                                " (схема и файл разошлись)"
                );
            }
            return row;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Хранение в файле
    // 1) Int length
    // 2) tombstone строка удалена
    // 3) byte[] data

    /**
     * Дописывает новую запись в конец файла данных.
     *
     * @param file      открытый файл таблицы
     * @param schema    схема таблицы
     * @param row       записываемая строка
     * @param tombstone флаг удалённой записи ({@code true}, если запись сразу помечается удалённой)
     * @return байтовое смещение (offset), по которому запись сохранена в файле
     * @throws IOException             при ошибке записи на диск
     * @throws NonUniqueValueException если нарушена уникальность полей для активной записи
     */
    public static long write(RandomAccessFile file, TableSchema schema,
                             Map<String, Object> row, boolean tombstone) throws IOException {
        if (!tombstone && !slowCheckUnique(file, schema, row)) {
            throw new NonUniqueValueException("Строка " + row.values() + " содержит неуникальные поля");
        }
        byte[] container = encodePayLoad(schema, row);
        long offset = file.length();
        file.seek(offset);
        file.writeInt(container.length);
        file.writeByte(tombstone ? 1 : 0);
        file.write(container);
        return offset;
    }

    /**
     * Считывает запись из файла по заданному смещению.
     *
     * @param file   открытый файл таблицы
     * @param schema схема таблицы
     * @param offset байтовое смещение начала записи
     * @return словарь значений строки или {@code null}, если запись помечена как удалённая ({@code tombstone})
     * @throws IOException при ошибке чтения или неожиданном конце файла
     */
    public static Map<String, Object> read(RandomAccessFile file,
                                           TableSchema schema, long offset) throws IOException {
        file.seek(offset);
        int length = file.readInt();
        int isDeleteByte = file.readByte();

        if ((isDeleteByte & 1) != 0) {
            // Не используем skipBytes: он может пропустить меньше байт, чем запрошено.
            file.seek(offset + 4L + 1L + length);
            return null;
        }

        byte[] container = new byte[length];
        file.readFully(container); // гарантирует полное заполнение, иначе бросает EOFException
        return decodePayLoad(schema, container);
    }

    // функция находящая смещение следующей записи, нужная для быстрого обхода файла

    /**
     * Вычисляет смещение следующей записи в файле без чтения её содержимого.
     *
     * @param file   открытый файл таблицы
     * @param offset смещение текущей записи
     * @return байтовое смещение начала следующей записи
     * @throws IOException при ошибке чтения заголовка записи
     */
    public static long nextOffset(RandomAccessFile file, long offset) throws IOException {
        file.seek(offset);
        int length = file.readInt();
        return offset + 4L + 1L + length;
    }

    // Пометить запись deleted по offset

    /**
     * Помечает запись по указанному смещению как удалённую, устанавливая байт {@code tombstone = 1}.
     *
     * @param file   открытый файл таблицы
     * @param offset байтовое смещение удаляемой записи
     * @throws IOException при ошибке записи в файл
     */
    public static void markDeleted(RandomAccessFile file, long offset) throws IOException {
        file.seek(offset + 4L);
        file.writeByte(1);
    }

    /**
     * Проверяет добавляемую строку на соблюдение уникальности полей полным перебором файла.
     *
     * @param file     открытый файл таблицы
     * @param schema   схема таблицы
     * @param rowToAdd проверяемая строка
     * @return {@code true}, если дубликатов по уникальным столбцам не найдено
     * @throws NonUniqueValueException если в файле уже существует активная запись с таким же уникальным значением
     */
    // Проверка новой записи на уникальность полным перебором
    public static boolean slowCheckUnique(RandomAccessFile file, TableSchema schema, Map<String, Object> rowToAdd) {
        long currentOffset = 0;
        try {
            long fileLength = file.length();
            while (currentOffset < fileLength) {
                long next = nextOffset(file, currentOffset);
                Map<String, Object> existingRow = read(file, schema, currentOffset);
                currentOffset = next;

                if (existingRow == null) {
                    continue;
                }

                for (ColumnDef col : schema.getColumns()) {
                    if (col.isUniquie()) {
                        Object existingVal = existingRow.get(col.name());
                        Object newVal = rowToAdd.get(col.name());
                        if (existingVal != null && existingVal.equals(newVal)) {
                            throw new NonUniqueValueException(col.name());
                        }
                    }
                }
            }
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Поиск всех PK по значению произвольного поля

    /**
     * Находит значения первичных ключей всех активных записей с заданным значением указанного столбца.
     *
     * @param file        открытый файл таблицы
     * @param schema      схема таблицы
     * @param columnName  имя столбца для фильтрации
     * @param targetValue искомое значение поля
     * @return список первичных ключей подходящих записей
     * @throws IOException       при ошибке чтения файла
     * @throws UnknownColumnName если указанный столбец отсутствует в схеме
     */
    public static List<Object> slowFindPksByColumn(RandomAccessFile file, TableSchema schema,
                                                   String columnName, Object targetValue) throws IOException {
        boolean columnExists = false;
        for (ColumnDef col : schema.getColumns()) {
            if (col.name().equals(columnName)) {
                columnExists = true;
                break;
            }
        }
        if (!columnExists) {
            throw new UnknownColumnName(columnName);
        }

        List<Object> matchingPks = new ArrayList<>();
        String pkColumnName = schema.pkColumn().name();
        long currentOffset = 0;
        long fileLength = file.length();

        while (currentOffset < fileLength) {
            long next = nextOffset(file, currentOffset);
            Map<String, Object> row = read(file, schema, currentOffset);
            currentOffset = next;

            if (row == null) {
                continue;
            }

            if (Objects.equals(row.get(columnName), targetValue)) {
                matchingPks.add(row.get(pkColumnName));
            }
        }

        return matchingPks;
    }

    // Поиск offset записи по значению PK (возвращает -1, если не найдено)

    /**
     * Находит байтовое смещение активной записи в файле по значению её первичного ключа.
     *
     * @param file     открытый файл таблицы
     * @param schema   схема таблицы
     * @param targetPk искомое значение первичного ключа (не {@code null})
     * @return смещение записи в файле или {@code -1}, если запись не найдена
     * @throws IOException          при ошибке чтения файла
     * @throws NullPointerException если {@code targetPk} равен {@code null}
     */
    public static long slowFindOffsetByPk(RandomAccessFile file, TableSchema schema, Object targetPk) throws IOException {
        if (targetPk == null) {
            throw new NullPointerException("Передан null PK для поиска");
        }

        String pkColumnName = schema.pkColumn().name();
        long currentOffset = 0;
        long fileLength = file.length();

        while (currentOffset < fileLength) {
            long recordOffset = currentOffset;
            long next = nextOffset(file, currentOffset);
            Map<String, Object> row = read(file, schema, currentOffset);
            currentOffset = next;

            if (row == null) {
                continue;
            }

            if (Objects.equals(row.get(pkColumnName), targetPk)) {
                return recordOffset;
            }
        }

        return -1;
    }

    // Очистка мусора в файле

    /**
     * Выполняет сжатие файла данных, перезаписывая во временный файл только активные записи
     * (без флага {@code tombstone}) и атомарно заменяя исходный файл.
     *
     * @param dataPath путь к файлу данных таблицы ({@code .dat})
     * @return количество сохранённых (активных) записей после очистки
     * @throws IOException при ошибке чтения, записи или перемещения файла
     */
    public static long compact(java.nio.file.Path dataPath) throws IOException {
        java.nio.file.Path tempPath = dataPath.resolveSibling(dataPath.getFileName() + ".tmp");
        long survivingRecords = 0;

        // 1. Открываем старый файл на чтение и новый временный на запись
        try (RandomAccessFile srcFile = new RandomAccessFile(dataPath.toFile(), "r");
             RandomAccessFile dstFile = new RandomAccessFile(tempPath.toFile(), "rw")) {

            long currentOffset = 0;
            long fileLength = srcFile.length();

            while (currentOffset < fileLength) {
                srcFile.seek(currentOffset);
                int length = srcFile.readInt();
                byte tombstone = srcFile.readByte();

                // Если запись живая (tombstone == 0) — копируем её байты во временный файл
                if ((tombstone & 1) == 0) {
                    byte[] payload = new byte[length];
                    srcFile.readFully(payload);

                    dstFile.writeInt(length);
                    dstFile.writeByte(0);
                    dstFile.write(payload);
                    survivingRecords++;
                }

                // Переходим к следующей записи в исходном файле
                currentOffset += 4L + 1L + length;
            }
        } // Оба RandomAccessFile автоматически закрываются здесь

        // 2. Безопасно заменяем старый файл новым сжатым файлом
        try {
            java.nio.file.Files.move(
                    tempPath,
                    dataPath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
            );
        } catch (IOException e) {
            // Убираем временный файл, чтобы не оставлять мусор при неудачном move
            try {
                java.nio.file.Files.deleteIfExists(tempPath);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }

        return survivingRecords;
    }

    /**
     * Выполняет сжатие файла данных с одновременным перестроением первичного индекса.
     *
     * @param dataPath путь к файлу данных таблицы ({@code .dat})
     * @param schema   схема таблицы для извлечения значений первичного ключа
     * @return обновлённый первичный индекс в формате {@code значение_PK -> новое_смещение}
     * @throws IOException при ошибке работы с файлами
     */
    public static Map<Object, Long> compactWithIndex(java.nio.file.Path dataPath, TableSchema schema) throws IOException {
        java.nio.file.Path tempPath = dataPath.resolveSibling(dataPath.getFileName() + ".tmp");
        Map<Object, Long> newPrimaryIndex = new java.util.HashMap<>();
        String pkColName = schema.pkColumn().name();

        try (RandomAccessFile srcFile = new RandomAccessFile(dataPath.toFile(), "r");
             RandomAccessFile dstFile = new RandomAccessFile(tempPath.toFile(), "rw")) {

            long currentOffset = 0;
            long fileLength = srcFile.length();

            while (currentOffset < fileLength) {
                srcFile.seek(currentOffset);
                int length = srcFile.readInt();
                byte tombstone = srcFile.readByte();

                if ((tombstone & 1) == 0) {
                    byte[] payload = new byte[length];
                    srcFile.readFully(payload);

                    // Декодируем запись, чтобы узнать её PK для нового смещения
                    Map<String, Object> row = decodePayLoad(schema, payload);
                    Object pkValue = row.get(pkColName);

                    // Запоминаем новое смещение в сжатом файле
                    long newOffset = dstFile.length();
                    newPrimaryIndex.put(pkValue, newOffset);

                    dstFile.writeInt(length);
                    dstFile.writeByte(0);
                    dstFile.write(payload);
                }

                currentOffset += 4L + 1L + length;
            }
        }

        try {
            java.nio.file.Files.move(
                    tempPath,
                    dataPath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
            );
        } catch (IOException e) {
            try {
                java.nio.file.Files.deleteIfExists(tempPath);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }

        return newPrimaryIndex;
    }
}