package main.core.domainExceptions;

public class MaxLengthExceededException extends RuntimeException {

    public MaxLengthExceededException(String message) {
        super(message);
    }
}
