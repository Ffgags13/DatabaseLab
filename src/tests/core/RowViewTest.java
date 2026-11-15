package tests.core;

import main.core.ColumnType;
import main.core.RowView;
import main.core.domainExceptions.IncorrectTypeInConditionException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

class RowViewTest {
    private RowView rowView;

    @BeforeEach
    void setUp() {
        Object[] values = new Object[]{
                25,                         // 0: age (INT)
                "Ivan",                     // 1: name (STRING)
                true,                       // 2: active (BOOLEAN)
                LocalDate.of(2026, 5, 10),  // 3: reg_date (DATEDAYS)
                null                        // 4: nullable_score (DOUBLE)
        };
        ColumnType[] types = new ColumnType[]{
                ColumnType.INT,
                ColumnType.STRING,
                ColumnType.BOOLEAN,
                ColumnType.DATEDAYS,
                ColumnType.DOUBLE
        };
        String[] colNames = new String[]{"age", "name", "active", "reg_date", "nullable_score"};

        rowView = new RowView(values, types, colNames);
    }

    @Test
    void comparisons_validTypesTest() {
        Assertions.assertAll(
                () -> Assertions.assertTrue(rowView.eq(0, 25)),
                () -> Assertions.assertTrue(rowView.gt(0, 18)),
                () -> Assertions.assertTrue(rowView.lt(0, 30)),
                () -> Assertions.assertTrue(rowView.eq(1, "Ivan")),
                () -> Assertions.assertTrue(rowView.gt(1, "Anna")),
                () -> Assertions.assertTrue(rowView.eq(2, true)),
                () -> Assertions.assertTrue(rowView.gt(3, "2026-01-01")),
                () -> Assertions.assertTrue(rowView.lt(3, LocalDate.of(2026, 12, 31)))
        );
    }

    @Test
    void nullValuesTest() {
        Assertions.assertAll(
                () -> Assertions.assertTrue(rowView.isNull(4)),
                () -> Assertions.assertFalse(rowView.isNull(0)),
                () -> Assertions.assertFalse(rowView.eq(4, 10.5)),
                () -> Assertions.assertFalse(rowView.gt(4, 0.0)),
                () -> Assertions.assertFalse(rowView.lt(4, 100.0)),
                () -> Assertions.assertFalse(rowView.eq(0, null))
        );
    }

    @Test
    void incorrectTypeInCondition_throwsExceptionTest() {
        IncorrectTypeInConditionException ex = Assertions.assertThrows(
                IncorrectTypeInConditionException.class,
                () -> rowView.gt(0, "not_a_number")
        );
        Assertions.assertTrue(ex.getMessage().contains("age"));
    }
}
