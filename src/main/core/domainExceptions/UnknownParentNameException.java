package main.core.domainExceptions;

public class UnknownParentNameException extends DatabaseException {
    public UnknownParentNameException(String parentTableName) {
        super("Родительская таблица '" + parentTableName + "' для внешнего ключа не найдена");
    }
}
