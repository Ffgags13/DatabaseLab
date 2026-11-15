package main.core.domainExceptions;

public class AmountValuesException extends DatabaseException {
    public AmountValuesException(long expectedAmount) {
        super("Неверное количество значений для вставки. Ожидалось: " + expectedAmount);
    }
}
