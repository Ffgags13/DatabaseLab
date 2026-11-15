package main.core.domainExceptions;

public class NullValueException extends RuntimeException {
    public NullValueException(String message) {
        super(message);
    }
}
