package main.core.domainExceptions;

public class DuplicateColumnNameException extends DatabaseException {
    public DuplicateColumnNameException(String colName) {
        super("Имя столбца '" + colName + "' используется в схеме дважды");
    }
}