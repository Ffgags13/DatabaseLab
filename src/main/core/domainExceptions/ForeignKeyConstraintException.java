package main.core.domainExceptions;

public class ForeignKeyConstraintException extends DatabaseException {
    public ForeignKeyConstraintException(String message) {
        super(message);
    }
}