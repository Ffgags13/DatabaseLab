package main.core.domainExceptions;

public class NonUniqueValueException extends DatabaseException {
    public NonUniqueValueException(String colName) {
        super("Нарушение уникальности значения в столбце '" + colName + "'");
    }
}
