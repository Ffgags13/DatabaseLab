package main.core.domainExceptions;

public class UnknownColumnName extends DatabaseException {
    public UnknownColumnName() {
        super("Обнаружено обращение к несуществующему столбцу");
    }

    public UnknownColumnName(String colName) {
        super("Столбец с именем '" + colName + "' не найден в схеме таблицы");
    }
}
