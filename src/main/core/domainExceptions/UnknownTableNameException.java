package main.core.domainExceptions;

public class UnknownTableNameException extends DatabaseException {
    public UnknownTableNameException(String tableName) {
        super("Таблица с именем '" + tableName + "' не найдена");
    }
}
