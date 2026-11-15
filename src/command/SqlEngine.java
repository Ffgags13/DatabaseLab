package command;

import core.Catalog;
import core.TableSchema;
import query.Ast.Statement;
import query.Parser;

import java.io.IOException;
import java.util.Objects;

public final class SqlEngine {

    private final Catalog catalog;
    private final CommandTranslator translator;
    private final CommandExecutor executor;

    public SqlEngine(Catalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.translator = new CommandTranslator(catalog);
        this.executor = new CommandExecutor(catalog);
    }

    public CommandExecutor.Result run(String sql) throws IOException {
        Statement stmt = Parser.parse(sql);
        Command cmd = translator.translate(stmt);
        return executor.execute(cmd);
    }

    public CommandExecutor.Result runAndPrint(String sql) throws IOException {
        CommandExecutor.Result res = run(sql);
        switch (res) {
            case CommandExecutor.Result.Rows r -> {
                String table = null;
                Statement stmt = Parser.parse(sql);
                if (stmt instanceof query.Ast.Select sel) table = sel.table();
                TableSchema schema = table == null ? null : catalog.getSchema(table);
                System.out.println(TablePrinter.print(schema, null, r.rows()));
            }
            case CommandExecutor.Result.Count c -> System.out.println(c.count() + " row(s) affected");
            case CommandExecutor.Result.Ok ok -> System.out.println("OK");
        }
        return res;
    }
}