package main.core.domainExceptions;

public class TableAlreadyExistsException extends DatabaseException {
    public TableAlreadyExistsException(String tableName) {
        super("Таблица с именем '" + tableName + "' уже существует");
    }
}
