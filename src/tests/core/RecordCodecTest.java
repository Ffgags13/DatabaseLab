package tests.core;

import main.core.*;
import main.core.domainExceptions.NonUniqueValueException;
import main.core.domainExceptions.NullValueException;
import main.core.domainExceptions.UnknownColumnName;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class RecordCodecTest {
    @TempDir
    Path tempDir;

    private TableSchema schema;
    private Path dataFile;

    @BeforeEach
    void setUp() {
        ColumnDef[] cols = new ColumnDef[]{
                new ColumnDef("id", ColumnType.INT, false, true),
                new ColumnDef("email", ColumnType.STRING, false, true),
                new ColumnDef("bio", ColumnType.STRING, true, false)
        };
        schema = new TableSchema("users", cols);
        dataFile = tempDir.resolve("users.dat");
    }

    private Map<String, Object> createRow(Integer id, String email, String bio) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("email", email);
        row.put("bio", bio);
        return row;
    }

    @Test
    void encodeDecodePayload_withNullableTest() {
        Map<String, Object> row = createRow(1, "user@test.com", null);
        byte[] encoded = RecordCodec.encodePayLoad(schema, row);
        Map<String, Object> decoded = RecordCodec.decodePayLoad(schema, encoded);

        Assertions.assertAll(
                () -> Assertions.assertEquals(1, decoded.get("id")),
                () -> Assertions.assertEquals("user@test.com", decoded.get("email")),
                () -> Assertions.assertNull(decoded.get("bio"))
        );
    }

    @Test
    void encodePayload_validationErrorsTest() {
        Map<String, Object> nullPkRow = createRow(null, "a@b.c", "text");
        Map<String, Object> nullNotNullColRow = createRow(1, null, "text");

        Assertions.assertAll(
                () -> Assertions.assertThrows(NullValueException.class, () -> RecordCodec.encodePayLoad(schema, nullPkRow)),
                () -> Assertions.assertThrows(NullValueException.class, () -> RecordCodec.encodePayLoad(schema, nullNotNullColRow))
        );
    }

    @Test
    void writeReadAndUniqueCheckTest() throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(dataFile.toFile(), "rw")) {
            long offset1 = RecordCodec.write(raf, schema, createRow(1, "one@mail.ru", "First"), false);
            long offset2 = RecordCodec.write(raf, schema, createRow(2, "two@mail.ru", null), false);

            Map<String, Object> read1 = RecordCodec.read(raf, schema, offset1);
            Map<String, Object> read2 = RecordCodec.read(raf, schema, offset2);

            Assertions.assertAll(
                    () -> Assertions.assertEquals("one@mail.ru", read1.get("email")),
                    () -> Assertions.assertNull(read2.get("bio")),
                    () -> Assertions.assertThrows(NonUniqueValueException.class,
                            () -> RecordCodec.write(raf, schema, createRow(3, "one@mail.ru", "Dup email"), false))
            );
        }
    }

    @Test
    void markDeletedAndSlowFindTest() throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(dataFile.toFile(), "rw")) {
            long off1 = RecordCodec.write(raf, schema, createRow(10, "a@mail.ru", "dev"), false);
            long off2 = RecordCodec.write(raf, schema, createRow(20, "b@mail.ru", "dev"), false);
            long off3 = RecordCodec.write(raf, schema, createRow(30, "c@mail.ru", "qa"), false);

            RecordCodec.markDeleted(raf, off2);

            List<Object> devs = RecordCodec.slowFindPksByColumn(raf, schema, "bio", "dev");
            long foundOff3 = RecordCodec.slowFindOffsetByPk(raf, schema, 30);
            long deletedOff2 = RecordCodec.slowFindOffsetByPk(raf, schema, 20);

            Assertions.assertAll(
                    () -> Assertions.assertNull(RecordCodec.read(raf, schema, off2)),
                    () -> Assertions.assertEquals(List.of(10), devs),
                    () -> Assertions.assertEquals(off3, foundOff3),
                    () -> Assertions.assertEquals(-1L, deletedOff2),
                    () -> Assertions.assertThrows(UnknownColumnName.class,
                            () -> RecordCodec.slowFindPksByColumn(raf, schema, "unknown_col", "dev"))
            );
        }
    }

    @Test
    void compactFile_removesTombstonesAndShrinksSizeTest() throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(dataFile.toFile(), "rw")) {
            RecordCodec.write(raf, schema, createRow(1, "1@mail.ru", "keep"), false);
            long delOff = RecordCodec.write(raf, schema, createRow(2, "2@mail.ru", "remove_me_long_text"), false);
            RecordCodec.write(raf, schema, createRow(3, "3@mail.ru", "keep_too"), false);
            RecordCodec.markDeleted(raf, delOff);
        }

        long sizeBefore = Files.size(dataFile);
        long aliveCount = RecordCodec.compact(dataFile);
        long sizeAfter = Files.size(dataFile);

        Assertions.assertAll(
                () -> Assertions.assertEquals(2L, aliveCount),
                () -> Assertions.assertTrue(sizeAfter < sizeBefore)
        );
    }
}
