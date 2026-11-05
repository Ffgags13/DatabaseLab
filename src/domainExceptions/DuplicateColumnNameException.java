package domainExceptions;

public class DuplicateColumnNameException extends RuntimeException {
    public DuplicateColumnNameException(String message) {
        super(message);
    }
}