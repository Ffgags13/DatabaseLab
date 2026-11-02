package core;

import core.domainExceptions.NullableKeyException;

import javax.swing.plaf.IconUIResource;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public class Test {
    public static void main(String[] args) {
        //Test0 создание schema с нулевым т.е pk стобцом nullable

        try {
            ColumnDef[] testPKerr = {new ColumnDef("Test int",ColumnType.INT, true)};
            TableSchema testShema = new TableSchema("test", testPKerr);
        }
        catch (NullableKeyException e){
            System.out.println(e.getMessage());
            System.out.println("test0 pass");

        }

        //Test1 decode(encode(row)) == row, row содержит все 5 доступных типов данных
        try {
            ColumnDef[] colsAllTypes = {new ColumnDef("Test int",ColumnType.INT, false),
                    new ColumnDef("Test double",ColumnType.DOUBLE, true),
                    new ColumnDef("Test bool", ColumnType.BOOLEAN, false),
                    new ColumnDef("Test string", ColumnType.STRING, false),
                    new ColumnDef("Test Date", ColumnType.DATE, false)};

            TableSchema allTypesTable = new TableSchema("allTypes", colsAllTypes);

            Map<String, Object> row  = new LinkedHashMap<>();

            row.put("Test int", 123);
            row.put("Test double",123.123);
            row.put("Test bool", false);
            row.put("Test string", "abc");
            LocalDate specificDate1 = LocalDate.of(2024, 11, 3);
            row.put("Test Date", specificDate1);
            byte [] out = RecordCodec.encodePayLoad(allTypesTable, row);
            if((RecordCodec.decodePayLoad(allTypesTable, out).equals(row))){
                System.out.println("Test1 passed");
            }
            else
                System.out.println("Test 1 failed");
        }
        catch (NullableKeyException e){
            System.out.println(e.getMessage());
            System.out.println("Test1 failed");

        }

        //Test2 неверный тип на входе в encode

        try{
            ColumnDef[] col= { new ColumnDef("Test int",ColumnType.INT, false)} ;
            TableSchema table = new TableSchema("abc", col);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("Test int", 123.123);
            RecordCodec.encodePayLoad(table, row);
            System.out.println("Test2 failed");
        }
        catch(IllegalArgumentException e){
            System.out.println(e.getMessage());
            System.out.println("Test2 passed");
        }

        //Test3 лишние байты в decode

        try{
            ColumnDef[] col= { new ColumnDef("Test int",ColumnType.INT, false)} ;
            TableSchema table = new TableSchema("abc", col);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("Test int", 123);
            byte[] out = RecordCodec.encodePayLoad(table, row);
            byte[] result = Arrays.copyOf(out, out.length + 1);
            result[result.length - 1] = 40;
            RecordCodec.decodePayLoad(table, result);
            System.out.println("Test3 fail");
        }
        catch(IllegalStateException e){
            System.out.println(e.getMessage());
            System.out.println("Test3 passed");
        }

    }
}
