package main.core.domainExceptions;

public class KeyNotUniqueException extends RuntimeException {
    public KeyNotUniqueException(String message) {
        super(message);
    }
}
