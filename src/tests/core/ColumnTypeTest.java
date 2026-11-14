package tests.core;
import main.core.ColumnType;
import main.core.domainExceptions.MaxLengthExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.chrono.ChronoLocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Locale;

class ColumnTypeTest {
    private int integer1 = 100;
    private long smallLong = 5000L;
    private Long bigLong = Long.MAX_VALUE;
    private boolean bol = true;
    private  double dbl = 100.100;
    private float flt = 20.20f;
    private String str = "test";
    private String strMaxLength = "1111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111";
    private char[] chars = new char[]{'a', 'b', 'c'};
    private ByteArrayOutputStream outcontainer;
    private ByteArrayInputStream incontainer;
    private String datestr = "2026-11-14";
    private LocalDate dateloc = LocalDate.parse(datestr);
    private DataOutputStream outstream;
    private DataInputStream instream;
    private byte[] arra;


    @Test
    void requireType_intTest(){
        Assertions.assertAll(
                () ->Assertions.assertEquals(integer1, ColumnType.INT.requireType(integer1)),
                () -> Assertions.assertEquals(5000, ColumnType.INT.requireType(smallLong)),
                () -> Assertions.assertThrows(ArithmeticException.class, () -> ColumnType.INT.requireType(bigLong)),
                () -> Assertions.assertThrows(IllegalArgumentException.class, () -> ColumnType.INT.requireType(dbl))

        );
    }

    @Test
    void requireType_boolTest(){
        Assertions.assertAll(
                () ->Assertions.assertEquals(bol, ColumnType.BOOLEAN.requireType(bol)),
                () -> Assertions.assertThrows(IllegalArgumentException.class, () -> ColumnType.BOOLEAN.requireType(dbl))

        );
    }

    @Test
    void requireType_doubleTest(){
        Assertions.assertAll(
                () ->Assertions.assertEquals(dbl, ColumnType.DOUBLE.requireType(dbl)),
                () -> Assertions.assertEquals(20.20, (Double)ColumnType.DOUBLE.requireType(flt), 0.0001),
                () -> Assertions.assertEquals(bigLong.doubleValue(), (Double)ColumnType.DOUBLE.requireType(bigLong), 0.0001),
                () -> Assertions.assertThrows(IllegalArgumentException.class, () -> ColumnType.DOUBLE.requireType(str))

        );
    }

    @Test
    void requireType_stringTest(){
        Assertions.assertAll(
                () ->Assertions.assertEquals(str.getBytes(StandardCharsets.UTF_8), ColumnType.STRING.requireType(str)),
                () -> Assertions.assertEquals(Arrays.toString(chars).getBytes(StandardCharsets.UTF_8), ColumnType.STRING.requireType(chars)),
                () -> Assertions.assertThrows(MaxLengthExceededException.class, () -> ColumnType.STRING.requireType(strMaxLength)),
                () -> Assertions.assertThrows(IllegalArgumentException.class, () -> ColumnType.STRING.requireType(dbl))

        );
    }

    @Test
    void requireType_DATEDAYS(){
        Assertions.assertAll(
                () ->Assertions.assertEquals((long) integer1, ColumnType.DATEDAYS.requireType(integer1)),
                () -> Assertions.assertEquals(smallLong, ColumnType.DATEDAYS.requireType(smallLong)),
                () -> Assertions.assertEquals(LocalDate.parse(datestr).toEpochDay(), ColumnType.DATEDAYS.requireType(datestr)),
                () -> Assertions.assertEquals(dateloc.toEpochDay(), ColumnType.DATEDAYS.requireType(dateloc)),
                () -> Assertions.assertThrows(DateTimeParseException.class, () -> ColumnType.DATEDAYS.requireType(str)),
                () -> Assertions.assertThrows(IllegalArgumentException.class, () -> ColumnType.DATEDAYS.requireType(dbl))

        );
    }

    @Test
    void binaryReadWrite_INT(){
        outcontainer = new ByteArrayOutputStream(64);
        outstream = new DataOutputStream(outcontainer);
        ColumnType.INT.binaryWrite(outstream, integer1);
        incontainer = new ByteArrayInputStream(outcontainer.toByteArray());
        instream = new DataInputStream(incontainer);
        Assertions.assertEquals(integer1, ColumnType.INT.binaryRead(instream));

    }

    @Test
    void binaryReadWrite_DOUBLE(){
        outcontainer = new ByteArrayOutputStream(64);
        outstream = new DataOutputStream(outcontainer);
        ColumnType.DOUBLE.binaryWrite(outstream, dbl);
        incontainer = new ByteArrayInputStream(outcontainer.toByteArray());
        instream = new DataInputStream(incontainer);
        Assertions.assertEquals(dbl, ColumnType.DOUBLE.binaryRead(instream));

    }

    @Test
    void binaryReadWrite_BOOL(){
        outcontainer = new ByteArrayOutputStream(64);
        outstream = new DataOutputStream(outcontainer);
        ColumnType.INT.binaryWrite(outstream, integer1);
        incontainer = new ByteArrayInputStream(outcontainer.toByteArray());
        instream = new DataInputStream(incontainer);
        Assertions.assertEquals(integer1, ColumnType.INT.binaryRead(instream));

    }

    @Test
    void binaryReadWrite_DATEDAYS(){
        outcontainer = new ByteArrayOutputStream(64);
        outstream = new DataOutputStream(outcontainer);
        ColumnType.DATEDAYS.binaryWrite(outstream, dateloc);
        incontainer = new ByteArrayInputStream(outcontainer.toByteArray());
        instream = new DataInputStream(incontainer);
        Assertions.assertEquals(dateloc, ColumnType.DATEDAYS.binaryRead(instream));

    }

    @Test
    void binaryReadWrite_STRING(){
        outcontainer = new ByteArrayOutputStream(64);
        outstream = new DataOutputStream(outcontainer);
        ColumnType.STRING.binaryWrite(outstream, str);
        incontainer = new ByteArrayInputStream(outcontainer.toByteArray());
        instream = new DataInputStream(incontainer);
        Assertions.assertEquals(str, ColumnType.STRING.binaryRead(instream));

    }
    @Test
    void binaryReadWrite_INTDOUBLE(){
        outcontainer = new ByteArrayOutputStream(128);
        outstream = new DataOutputStream(outcontainer);
        ColumnType.DOUBLE.binaryWrite(outstream, dbl);
        ColumnType.INT.binaryWrite(outstream, integer1);
        incontainer = new ByteArrayInputStream(outcontainer.toByteArray());
        instream = new DataInputStream(incontainer);
        Assertions.assertEquals(dbl, ColumnType.DOUBLE.binaryRead(instream));
        Assertions.assertEquals(integer1, ColumnType.INT.binaryRead(instream));


    }
}
