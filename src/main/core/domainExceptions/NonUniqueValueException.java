package main.core.domainExceptions;

public class NonUniqueValueException extends RuntimeException {
    public NonUniqueValueException(String message) {
        super(message);
    }
}
