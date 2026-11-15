package main.core.domainExceptions;

public class NullableKeyException extends DatabaseException {
    public NullableKeyException() {
        super("Первичный ключ (первый столбец) не может быть nullable");
    }
}
