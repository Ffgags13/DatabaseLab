package main.core.domainExceptions;

public class KeyNotUniqueException extends DatabaseException {
    public KeyNotUniqueException() {
        super("Первичный ключ (первый столбец) должен быть unique");
    }
}
