package main.core.domainExceptions;

public class IncorrectType extends DatabaseException {
    public IncorrectType(String colName) {
        super("Передан объект неправильного типа в столбце '" + colName + "'");
    }

    public IncorrectType(String typeName, String colName) {
        super("Ожидается тип " + typeName + " в столбце '" + colName + "'");
    }
}
