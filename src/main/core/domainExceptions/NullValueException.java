package main.core.domainExceptions;

public class NullValueException extends DatabaseException {
    public NullValueException(String colName) {
        super("Попытка записи null в обязательный (NOT NULL) столбец '" + colName + "'");
    }
}
