package main.core;

import main.core.domainExceptions.MaxLengthExceededException;
import main.core.domainExceptions.NonUniqueValueException;
import main.core.domainExceptions.UnknownColumnName;

import java.io.*;
import java.util.LinkedHashMap;
import java.util.Map;


public class RecordCodec {
    private static final int DEFAULT_CONTAINER_SIZE = 64;

    public static byte[] encodePayLoad(TableSchema schema, Map<String, Object> row){
       //Проверяем PK
       if(row.get(schema.pkColumn().name()) == null){
           throw new NullPointerException("Передана строка с null PK");
       }
       try {
           ByteArrayOutputStream container = new ByteArrayOutputStream(DEFAULT_CONTAINER_SIZE);
           DataOutputStream outContainer = new DataOutputStream(container);
           for (ColumnDef col : schema.getColumns()) {
               Object item = row.get(col.name());
               int emptyCount = 0;
               if (item == null) {
                    ++emptyCount;
                    if(!col.nullable()){
                        throw new IllegalArgumentException("null в не nullable столбце " + col.name());
                    }
                    outContainer.writeByte(1);
                    continue;
               }
               outContainer.writeByte(0);
               try {
                   col.type().binaryWrite(outContainer, item);
               }
               catch(IllegalArgumentException e){
                   throw new IllegalArgumentException("В стобце: " + col.name() + " ожидается: "
                           + col.type() + ", получено: " + e.getMessage());
               }
               catch(MaxLengthExceededException e){
                   throw new MaxLengthExceededException("В столбце: "+ col.name() + " превышена максимальная длина String. ");
               }

               if(emptyCount + row.size() > schema.getColumns().length){
                   throw new UnknownColumnName("Обнаружена запись в столбец несуществующий в схеме: " + schema.getColumns());
               }
           }
           outContainer.flush();
           return container.toByteArray();
       }

       catch (IOException e) {
           throw new UncheckedIOException(e);
       }


    }

    public static Map<String, Object> decodePayLoad(TableSchema schema, byte[] container){
        try {
            DataInputStream inContainer = new DataInputStream(new ByteArrayInputStream(container));
            Map<String, Object> row = new LinkedHashMap<>();

            for (ColumnDef col : schema.getColumns()) {
                int nullFlag = inContainer.readUnsignedByte();
                if(nullFlag == 1){
                    row.put(col.name(), null);
                    continue;
                }
                Object item = col.type().binaryRead(inContainer);
                row.put(col.name(), item);
            }
            if(inContainer.available() != 0)
                throw new IllegalStateException(
                        "Остались лишние байты в container: " +
                                inContainer.available() +
                                " (схема и файл разошлись)"
                );
            return row;
        }
        catch (IOException e){
            throw new UncheckedIOException(e);
        }

    }

    //Хранение в файле
    // 1) Int length
    // 2) tombstone строка удалена
    // 3) byte[] data
    public static long write(RandomAccessFile file, TableSchema schema,
                             Map<String, Object> row, boolean tombstone) throws IOException{
        if(!tombstone && !slowCheckUnique(file, schema, row)){
            throw new NonUniqueValueException("Строка "+ row.values() + "содержит неуникальные поля");
        }
        byte[] container = encodePayLoad(schema, row);
        long offset = file.length();
        file.seek(offset);
        file.writeInt(container.length);
        file.writeByte(tombstone ? 1 : 0);
        file.write(container);
        return offset;
    }

    public static Map<String, Object> read(RandomAccessFile file,
                                           TableSchema schema, long offset) throws IOException{
        file.seek(offset);
        int length = file.readInt();
        int isDeleteByte = file.readByte();

        if((isDeleteByte & 1) != 0){
            file.skipBytes(length);
            return null;
        }

        byte[] container = new byte[length];
        file.readFully(container); //гарантирует полное заполнение, иначе бросает EOFException
        return decodePayLoad(schema, container);

    }

    // функция находящая смещение следующей записи, нужная для быстрого обхода файла
    public static long nextOffset(RandomAccessFile file, long offset)throws IOException {
        file.seek(offset);
        int length = file.readInt();
        return offset + 4 + 1 + length;
        // прибавляем размер записи к текущему смещению, получаем следующее смещение

    }

    //Пометить запись deleted по offset
    public static void markDeleted(RandomAccessFile file, long offset) throws IOException{
        file.seek(offset+4);
        file.writeByte(1);

    }


    // Проверка новой записи на уникальность полей полным перебором (исправленная версия)
    public static boolean slowCheckUnique(RandomAccessFile file, TableSchema schema, Map<String, Object> rowToAdd) {
        long currentOffset = 0;
        try {
            long fileLength = file.length();
            while (currentOffset < fileLength) {
                long next = nextOffset(file, currentOffset);
                Map<String, Object> existingRow = read(file, schema, currentOffset);
                currentOffset = next;

                // Пропускаем удаленные записи (tombstone == 1)
                if (existingRow == null) {
                    continue;
                }

                for (ColumnDef col : schema.getColumns()) {
                    if (col.isUniquie()) {
                        Object existingVal = existingRow.get(col.name());
                        Object newVal = rowToAdd.get(col.name());
                        // В SQL два NULL не считаются дубликатами, поэтому проверяем existingVal != null
                        if (existingVal != null && existingVal.equals(newVal)) {
                            return false;
                        }
                    }
                }
            }
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // 1. Медленный способ нахождения всех PK по значению произвольного поля
    public static java.util.List<Object> slowFindPksByColumn(RandomAccessFile file, TableSchema schema,
                                                             String columnName, Object targetValue) throws IOException {
        // Проверяем, существует ли вообще такой столбец в схеме
        boolean columnExists = false;
        for (ColumnDef col : schema.getColumns()) {
            if (col.name().equals(columnName)) {
                columnExists = true;
                break;
            }
        }
        if (!columnExists) {
            throw new UnknownColumnName("Столбец " + columnName + " не найден в схеме таблицы");
        }

        java.util.List<Object> matchingPks = new java.util.ArrayList<>();
        String pkColumnName = schema.pkColumn().name();
        long currentOffset = 0;
        long fileLength = file.length();

        while (currentOffset < fileLength) {
            long next = nextOffset(file, currentOffset);
            Map<String, Object> row = read(file, schema, currentOffset);
            currentOffset = next;

            // Пропускаем удаленные записи
            if (row == null) {
                continue;
            }

            Object cellValue = row.get(columnName);
            if (java.util.Objects.equals(cellValue, targetValue)) {
                matchingPks.add(row.get(pkColumnName));
            }
        }

        return matchingPks;
    }

    // 2. Медленный способ нахождения offset записи по значению PK (возвращает -1, если не найдено)
    public static long slowFindOffsetByPk(RandomAccessFile file, TableSchema schema, Object targetPk) throws IOException {
        if (targetPk == null) {
            throw new NullPointerException("Передан null PK для поиска");
        }

        String pkColumnName = schema.pkColumn().name();
        long currentOffset = 0;
        long fileLength = file.length();

        while (currentOffset < fileLength) {
            long next = nextOffset(file, currentOffset);
            Map<String, Object> row = read(file, schema, currentOffset);

            // Если запись живая и PK совпал — сразу возвращаем смещение до перехода к next
            if (row != null && java.util.Objects.equals(row.get(pkColumnName), targetPk)) {
                return currentOffset;
            }

            currentOffset = next;
        }

        return -1L; // Запись не найдена или была удалена
    }

    //Очистка мусора в файле
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
        } // Оба RandomAccessFile автоматически закрываются здесь!

        // 2. Безопасно заменяем старый файл новым сжатым файлом
        java.nio.file.Files.move(
                tempPath,
                dataPath,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
        );

        return survivingRecords;
    }

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

        java.nio.file.Files.move(
                tempPath,
                dataPath,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
        );

        return newPrimaryIndex;
    }
}
