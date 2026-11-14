package main.core.domainExceptions;

public class UnknownColumnName extends RuntimeException {
    public UnknownColumnName(String message) {
        super(message);
    }
}
