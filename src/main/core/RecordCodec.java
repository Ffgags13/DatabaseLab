package main.core;

import main.core.domainExceptions.MaxLengthExceededException;
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

    //Проверка новой записи на уникальность полей полным перебором
    public static boolean slowCheckUnique(RandomAccessFile file, TableSchema schema, Map<String, Object> rowToAdd){
        long currentOffset = 0;
        try {
            while (currentOffset <= file.length()) {
                Map<String, Object> rowExc = read(file, schema, currentOffset);
                currentOffset = nextOffset(file, currentOffset);
                for(ColumnDef col : schema.getColumns()){
                    if(col.isUniquie() && rowExc.get(col.name()) == rowToAdd.get(col.name())){
                        return false;
                    }
                }

            }
            return true;
        }
        catch(IOException e){
            throw new UncheckedIOException(e);
        }
    }


}
