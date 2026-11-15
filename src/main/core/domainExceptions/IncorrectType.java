package main.core.domainExceptions;

public class IncorrectType extends RuntimeException {
    public IncorrectType(String message) {
        super(message);
    }
}
