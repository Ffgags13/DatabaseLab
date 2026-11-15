package main.core.domainExceptions;

public class IncorrectTypeInConditionException extends RuntimeException {
    public IncorrectTypeInConditionException(String message) {
        super(message);
    }
}
