package main.ui;

import main.command.CommandException;
import main.command.DbCommandApi;

public class SqlExecutor {
    private final DbCommandApi api;

    public SqlExecutor(DbCommandApi api) {
        this.api = api;
    }

    public QueryResult execute(String rawSql) {
        if (rawSql == null) {
            return QueryResult.error("Запрос не может быть null.");
        }
        String cleaned = rawSql.strip();
        if (cleaned.length() <= 5) {
            return QueryResult.error("Длина запроса должна быть больше 5 символов.");
        }
        try {
            // вызов парсера
            return QueryResult.message("Запрос передан в парсер: " + cleaned);
        } catch (CommandException e) {
            return QueryResult.error(e.getMessage());
        } catch (RuntimeException e) {
            return QueryResult.error("Ошибка парсинга: " + e.getMessage());
        }
    }
}
