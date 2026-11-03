package core;

import core.domainExceptions.NullableKeyException;

import java.io.File;
import java.io.RandomAccessFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public class Tests {

    public static void main(String[] args) {
        System.out.println("=== НАЧАЛО РУЧНОГО ТЕСТИРОВАНИЯ ===\n");

        runEncodeTests();
        runWriteReadTest();

        System.out.println("\n=== ТЕСТИРОВАНИЕ ЗАВЕРШЕНО ===");
    }

    private static void runEncodeTests() {
        // Test0: Проверка исключения NullableKeyException
        try {
            ColumnDef[] testPKerr = {new ColumnDef("Test int", ColumnType.INT, true)};
            new TableSchema("test", testPKerr);
            System.out.println("Test 0 FAILED (Ожидалась ошибка NullableKeyException, но её не было)");
        } catch (NullableKeyException e) {
            System.out.println("Test 0 PASSED (Ошибка поймана: " + e.getMessage() + ")");
        }

        // Test1: decode(encode(row)) == row
        try {
            ColumnDef[] colsAllTypes = {
                    new ColumnDef("Test int", ColumnType.INT, false),
                    new ColumnDef("Test double", ColumnType.DOUBLE, true),
                    new ColumnDef("Test bool", ColumnType.BOOLEAN, false),
                    new ColumnDef("Test string", ColumnType.STRING, false),
                    new ColumnDef("Test Date", ColumnType.DATE, false)};

            TableSchema allTypesTable = new TableSchema("allTypes", colsAllTypes);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("Test int", 123);
            row.put("Test double", 123.123);
            row.put("Test bool", false);
            row.put("Test string", "abc");
            row.put("Test Date", LocalDate.of(2024, 11, 3));

            byte[] out = RecordCodec.encodePayLoad(allTypesTable, row);
            Map<String, Object> decoded = RecordCodec.decodePayLoad(allTypesTable, out);

            if (row.equals(decoded)) {
                System.out.println("Test 1 PASSED (Данные успешно совпали)");
            } else {
                System.out.println("Test 1 FAILED (Декодированные данные отличаются от исходных)");
            }
        } catch (Exception e) {
            System.out.println("Test 1 FAILED с исключением: " + e.getMessage());
        }

        // Test2: Неверный тип данных на входе
        try {
            ColumnDef[] col = {new ColumnDef("Test int", ColumnType.INT, false)};
            TableSchema table = new TableSchema("abc", col);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("Test int", 123.123); // double вместо int
            RecordCodec.encodePayLoad(table, row);
            System.out.println("Test 2 FAILED (Ожидалась IllegalArgumentException)");
        } catch (IllegalArgumentException e) {
            System.out.println("Test 2 PASSED (Ошибка типа поймана: " + e.getMessage() + ")");
        }

        // Test3: Лишние байты в decode
        try {
            ColumnDef[] col = {new ColumnDef("Test int", ColumnType.INT, false)};
            TableSchema table = new TableSchema("abc", col);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("Test int", 123);
            byte[] out = RecordCodec.encodePayLoad(table, row);
            byte[] result = Arrays.copyOf(out, out.length + 1);
            result[result.length - 1] = 40; // Дописываем лишний байт

            RecordCodec.decodePayLoad(table, result);
            System.out.println("Test 3 FAILED (Ожидалась IllegalStateException)");
        } catch (IllegalStateException e) {
            System.out.println("Test 3 PASSED (Ошибка структуры поймана: " + e.getMessage() + ")");
        }
    }

    private static void runWriteReadTest() {
        Path myTempDir = null;
        File file = null;

        try {
            myTempDir = Files.createTempDirectory("manual_test_dir");
            Path filePath = myTempDir.resolve("test-file.txt");
            file = filePath.toFile();

            ColumnDef[] colsAllTypes = {
                    new ColumnDef("Test int", ColumnType.INT, false),
                    new ColumnDef("Test double", ColumnType.DOUBLE, true),
                    new ColumnDef("Test bool", ColumnType.BOOLEAN, false),
                    new ColumnDef("Test string", ColumnType.STRING, false),
                    new ColumnDef("Test Date", ColumnType.DATE, false)};

            TableSchema table = new TableSchema("allTypes", colsAllTypes);

            // Создаем две разные записи для тестов
            Map<String, Object> row1 = new LinkedHashMap<>();
            row1.put("Test int", 111);
            row1.put("Test double", 11.11);
            row1.put("Test bool", true);
            row1.put("Test string", "first");
            row1.put("Test Date", LocalDate.of(2026, 1, 1));

            Map<String, Object> row2 = new LinkedHashMap<>();
            row2.put("Test int", 222);
            row2.put("Test double", 22.22);
            row2.put("Test bool", false);
            row2.put("Test string", "second");
            row2.put("Test Date", LocalDate.of(2026, 2, 2));

            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {

                System.out.println("\n--- Тесты дисковой подсистемы (RAF) ---");

                // ==========================================
                // Сценарий 1: Записали одну -> read(off) вернула её же
                // ==========================================
                long offset1 = RecordCodec.write(raf, table, row1, false);
                Map<String, Object> readRow1 = RecordCodec.read(raf, table, offset1);

                if (row1.equals(readRow1)) {
                    System.out.println("Сценарий 1 PASSED (Одна запись успешно сохранена и прочитана)");
                } else {
                    System.out.println("Сценарий 1 FAILED (Данные первой записи искажены)");
                }

                // ==========================================
                // Сценарий 2: Записали две -> обе читаются по своим offset-ам
                // ==========================================
                long offset2 = RecordCodec.write(raf, table, row2, false);

                Map<String, Object> checkRow1 = RecordCodec.read(raf, table, offset1);
                Map<String, Object> checkRow2 = RecordCodec.read(raf, table, offset2);

                if (row1.equals(checkRow1) && row2.equals(checkRow2)) {
                    System.out.println("Сценарий 2 PASSED (Обе записи корректно доступны по своим оффсетам)");
                } else {
                    System.out.println("Сценарий 2 FAILED (Ошибка при параллельном чтении записей)");
                }

                // ==========================================
                // Сценарий 3: Записали с tombstone=true -> read вернул null, но nextOffset прыгнул корректно
                // ==========================================
                // Записываем row1 как удаленную (tombstone = true)
                long offsetTombstone = RecordCodec.write(raf, table, row1, true);

                // Проверяем, что read возвращает null для удаленной записи
                Map<String, Object> readTombstone = RecordCodec.read(raf, table, offsetTombstone);

                // Проверяем, прыгнул ли nextOffset.
                // Предполагается, что RecordCodec.write возвращает позицию в RAF после записи (nextOffset)
                // Если ваш метод write возвращает ТОЛЬКО начальный offset, вам нужно получить следующий оффсет
                // встроенным в ваш RecordCodec способом (например, через размер записи или текущий raf.getFilePointer())
                long nextOffsetAfterTombstone = raf.getFilePointer();

                if (readTombstone == null && nextOffsetAfterTombstone > offsetTombstone) {
                    System.out.println("Сценарий 3 PASSED (Tombstone вернул null, указатель сместился вперед)");
                } else {
                    System.out.println("Сценарий 3 FAILED (Удаленная запись вернула данные ИЛИ оффсет не изменился)");
                }

                // ==========================================
                // Сценарий 4: nextOffset для последней записи == raf.length()
                // ==========================================
                // Записываем финальную запись
                long lastRecordOffset = RecordCodec.write(raf, table, row2, false);
                long nextOffsetForLast = raf.getFilePointer(); // текущий указатель сразу после записи
                long totalFileLength = raf.length();

                if (nextOffsetForLast == totalFileLength) {
                    System.out.println("Сценарий 4 PASSED (nextOffset последней записи равен длине файла: " + totalFileLength + " байт)");
                } else {
                    System.out.println("Сценарий 4 FAILED (nextOffset=" + nextOffsetForLast + " не совпадает с длиной файла=" + totalFileLength + ")");
                }
            }

        } catch (IOException e) {
            System.out.println("Критическая ошибка ввода-вывода: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Удаление временных файлов за собой
            if (file != null && file.exists()) {
                file.delete();
            }
            if (myTempDir != null) {
                try {
                    Files.deleteIfExists(myTempDir);
                } catch (IOException ignored) {}
            }
        }
    }
}
