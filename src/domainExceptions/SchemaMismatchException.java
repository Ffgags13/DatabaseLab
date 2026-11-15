package domainExceptions;

/**
 * Данные не соответствуют схеме таблицы.
 * Доменное {@link RuntimeException}: ловится на границе приложения (GUI, парсер),
 * несёт контекст ({@link #column()}, {@link #expected()}, {@link #actual()}).
 */
public class SchemaMismatchException extends RuntimeException {

    public enum Kind {
        UNKNOWN_COLUMN,     // колонки нет в схеме
        TYPE_MISMATCH,      // тип значения не тот
        NULL_IN_REQUIRED,   // null в nullable=false
        MISSING_REQUIRED    // обязательное поле не передано
    }

    private final Kind kind;
    private final String column;
    private final String expected;
    private final Object actual;
    private final String table;

    public SchemaMismatchException(Kind kind, String column, String expected,
                                   Object actual, String table, String message) {
        super(message);
        this.kind = kind;
        this.column = column;
        this.expected = expected;
        this.actual = actual;
        this.table = table;
    }

    public static SchemaMismatchException unknownColumn(String column) {
        return unknownColumn(column, null);
    }
    public static SchemaMismatchException unknownColumn(String column, String table) {
        String where = (table == null ? "" : " в таблице «" + table + "»");
        return new SchemaMismatchException(Kind.UNKNOWN_COLUMN, column, null, null, table,
                "Колонки «" + column + "» нет" + where);
    }

    public static SchemaMismatchException typeMismatch(String column,
                                                       core.ColumnType expected,
                                                       Object actual) {
        return typeMismatch(column, expected, actual, null);
    }
    public static SchemaMismatchException typeMismatch(String column,
                                                       core.ColumnType expected,
                                                       Object actual, String table) {
        String exp = expected == null ? "?" : expected.name();
        String act = actual == null ? "null" : actual.getClass().getSimpleName();
        String where = (table == null ? "" : " в таблице «" + table + "»");
        return new SchemaMismatchException(Kind.TYPE_MISMATCH, column, exp, act, table,
                "Колонка «" + column + "»" + where + " ожидает " + exp + ", получено " + act);
    }

    public static SchemaMismatchException nullInRequired(String column) {
        return nullInRequired(column, null);
    }
    public static SchemaMismatchException nullInRequired(String column, String table) {
        String where = (table == null ? "" : " в таблице «" + table + "»");
        return new SchemaMismatchException(Kind.NULL_IN_REQUIRED, column, "NOT NULL",
                null, table, "Колонка «" + column + "»" + where + " не может быть NULL");
    }

    public static SchemaMismatchException missingRequired(String column) {
        return missingRequired(column, null);
    }
    public static SchemaMismatchException missingRequired(String column, String table) {
        String where = (table == null ? "" : " в таблице «" + table + "»");
        return new SchemaMismatchException(Kind.MISSING_REQUIRED, column, "REQUIRED",
                null, table, "Не задано обязательное поле «" + column + "»" + where);
    }

    public Kind kind() { return kind; }
    public String column() { return column; }
    public String expected() { return expected; }
    public Object actual() { return actual; }
    public String table() { return table; }

    @Override
    public String toString() {
        return "SchemaMismatchException{kind=" + kind + ", table=" + table
                + ", column=" + column + ", expected=" + expected
                + ", actual=" + actual + ", message=" + getMessage() + '}';
    }
}