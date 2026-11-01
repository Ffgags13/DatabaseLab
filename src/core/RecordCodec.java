package core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import core.domainExceptions.NullableKeyException;
public class RecordCodec {
    private static final int DEFAULT_CONTAINER_SIZE = 64;

    private static int requireInt(String colName, Object item){
        switch (item){
            case Integer i -> {
                return i;
            }
            case Long l -> {
                return Math.toIntExact(l);
            }
            default -> throw new IllegalArgumentException("Колонка " + colName + "ожидает INT, получено:" + item.getClass().getSimpleName());
        }
    }
    private static double requireDouble(String colName, Object item){
        switch (item){
            case Double d -> {
                return d;
            }
            case Float f-> {
                return f;
            }
            case Byte b -> {
                return b;
            }
            case Integer i -> {
                return i;
            }
            case Long l -> {
                return l;
            }
            default -> {
                throw new IllegalArgumentException("Колонка "
                    + colName + "ожидает DOUBLE, получено:"
                    + item.getClass().getSimpleName());
            }
        }
    }
    private static boolean requireBoolean(String colName, Object item){

        if(item instanceof Boolean b){
            return b;}
        throw new IllegalArgumentException("Колонка "
                        + colName + "ожидает Boolean, получено:"
                        + item.getClass().getSimpleName());
    }
    private static String requireString(String colName, Object item){

        if(item instanceof String s){
            return s;}
        throw new IllegalArgumentException("Колонка "
                + colName + "ожидает String, получено:"
                + item.getClass().getSimpleName());
    }
    private static LocalDate requireDate(String colName, Object item){

        if(item instanceof LocalDate d){
            return d;}
        throw new IllegalArgumentException("Колонка "
                + colName + "ожидает Date, получено:"
                + item.getClass().getSimpleName());
    }


    public static byte[] encodePayLoad(TableSchema schema, Map<String, Object> row){
       //Проверяем PK
       if(row.get(schema.pkColumn().name()) == null){
           throw new NullPointerException("Передана строка с нулевым PK");
       }
       try {
           ByteArrayOutputStream container = new ByteArrayOutputStream(DEFAULT_CONTAINER_SIZE);
           DataOutputStream outContainer = new DataOutputStream(container);
           for (ColumnDef col : schema.getColumns()) {
               Object item = row.get(col.name());
               if (item == null) {
                    outContainer.writeByte(1);
                    continue;
               }
               outContainer.writeByte(0);

               switch(col.type()){
                   case INT -> outContainer.writeInt(requireInt(col.name(),item));
                   case DOUBLE -> outContainer.writeDouble(requireDouble(col.name(),item));
                   case BOOLEAN -> outContainer.writeBoolean(requireBoolean(col.name(),item));
                   //String хранится в виде Short length + byte[] data
                   case STRING -> {
                       byte [] data = (requireString(col.name(), item)).getBytes(StandardCharsets.UTF_8);
                       if(data.length > Short.MAX_VALUE)
                           throw new IllegalArgumentException("Строка слишком длинная для колонки "
                           + col.name() + ":" + data.length + " байт + (max " + Short.MAX_VALUE + ")");
                       outContainer.writeShort(data.length);
                       outContainer.write(data);
                   }
                   //Date хранится как количество дней
                   case DATE -> outContainer.writeLong(requireDate(col.name(), item).toEpochDay());
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
                Object item = switch(col.type()){
                    case INT -> inContainer.readInt();
                    case DOUBLE -> inContainer.readDouble();
                    case BOOLEAN -> inContainer.readBoolean();
                    case DATE -> LocalDate.ofEpochDay(inContainer.readLong());
                    case STRING -> {
                        int length = inContainer.readUnsignedShort();
                        byte[] data = new byte[length];
                        inContainer.readFully(data);
                        yield new String(data, StandardCharsets.UTF_8);
                    }
                };
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
}
