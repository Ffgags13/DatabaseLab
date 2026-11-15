package main.core.domainExceptions;

public class IncorrectTypeInConditionException extends DatabaseException {
    public IncorrectTypeInConditionException(String colName) {
        super("Неправильный тип данных в условии фильтрации по столбцу '" + colName + "'");
    }
}
