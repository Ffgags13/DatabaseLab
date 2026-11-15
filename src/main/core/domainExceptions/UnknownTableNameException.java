package main.core.domainExceptions;

public class UnknownTableNameException extends RuntimeException {
    public UnknownTableNameException(String message) {
        super(message);
    }
}
