package domainExceptions;

public class NullableKeyException extends RuntimeException {
    public NullableKeyException(String message) {
        super(message);
    }
}