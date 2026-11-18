package main.ui;

import main.command.CommandException;
import main.command.CommandExecutor;
import main.command.DbCommandApi;
import main.core.TableSchema;
import main.core.domainExceptions.DatabaseException;
import main.query.Ast;
import main.query.ParseException;
import main.query.Parser;

import java.util.List;
import java.util.Map;

/**
 * Прослойка между консольным UI и парсером/исполнителем SQL-запросов.
 * <p>
 * Отвечает за первичную валидацию входной строки, передачу её на разбор в {@link Parser},
 * выполнение через {@link CommandExecutor} и упаковку результата или перехваченной ошибки в {@link QueryResult}.
 */
public class SqlExecutor {
    private final DbCommandApi api;
    private final CommandExecutor executor;

    /**
     * Создаёт исполнитель SQL-запросов, привязанный к указанному фасаду базы данных.
     *
     * @param api - фасад для выполнения команд над базой данных
     */
    public SqlExecutor(DbCommandApi api) {
        this.api = api;
        this.executor = new CommandExecutor(api);
    }

    /**
     * Валидирует сырую SQL-строку, запускает парсинг и выполнение запроса,
     * возвращая унифицированный ответ для отображения в консоли.
     *
     * @param rawSql - исходная строка запроса, введённая пользователем
     * @return объект {@link QueryResult} с данными выборки, сообщением об успехе или описанием ошибки
     */
    @SuppressWarnings("unchecked")
    public QueryResult execute(String rawSql) {
        if (rawSql == null) {
            return QueryResult.error("Запрос не может быть null.");
        }

        String cleaned = rawSql.strip();
        if (cleaned.length() <= 5) {
            return QueryResult.error("Длина запроса должна быть больше 5 символов.");
        }

        if (cleaned.endsWith(";")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).strip();
        }

        try {
            Ast.Statement stmt = Parser.parse(cleaned);
            Object rawResult = executor.execute(stmt);

            if (stmt instanceof Ast.Select sel) {
                if (rawResult instanceof List<?> rows) {
                    TableSchema schema = api.getSchema(sel.table());
                    return QueryResult.tableRows(schema, (List<Object[]>) rows);
                }
                if (rawResult instanceof Map<?, ?> cols) {
                    return QueryResult.tableColumns((Map<String, Object[]>) cols);
                }
            }

            if (rawResult instanceof Long count) {
                return QueryResult.message("Запрос выполнен успешно. Затронуто строк: " + count);
            }

            return QueryResult.message("Команда успешно выполнена.");

        } catch (ParseException e) {
            return QueryResult.error("Синтаксическая ошибка: " + e.getMessage());
        } catch (CommandException | DatabaseException e) {
            return QueryResult.error(e.getMessage());
        } catch (RuntimeException e) {
            return QueryResult.error("Ошибка выполнения: " + e.getMessage());
        }
    }
}