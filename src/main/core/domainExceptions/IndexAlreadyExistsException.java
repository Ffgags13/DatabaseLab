package main.core.domainExceptions;

public class IndexAlreadyExistsException extends DatabaseException {
    public IndexAlreadyExistsException(String colName) {
        super("Индекс для столбца '" + colName + "' уже существует");
    }
}
