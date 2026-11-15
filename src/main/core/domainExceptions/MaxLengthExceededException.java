package main.core.domainExceptions;

public class MaxLengthExceededException extends DatabaseException {
    public MaxLengthExceededException(String colName) {
        super("Превышена максимальная длина строки в столбце '" + colName + "'");
    }
}
