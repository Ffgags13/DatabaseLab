package main.core.domainExceptions;

public class UnknownParentNameException extends RuntimeException {
    public UnknownParentNameException(String message) {
        super(message);
    }
}
