package command;

import core.Catalog;
import core.ColumnDef;
import core.Table;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CommandExecutor {

    public sealed interface Result {
        record Rows(List<Map<String, Object>> rows) implements Result {}
        record Count(long count) implements Result {}
        record Ok() implements Result {}
    }

    private final Catalog catalog;

    public CommandExecutor(Catalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public Result execute(Command cmd) throws IOException {
        return switch (cmd) {
            case Command.CreateTable ct -> {
                ColumnDef[] cols = ct.columns().toArray(new ColumnDef[0]);
                catalog.createTable(ct.name(), cols);
                yield new Result.Ok();
            }
            case Command.DropTable dt -> {
                catalog.dropTable(dt.name());
                yield new Result.Ok();
            }
            case Command.CreateIndex ci -> {
                catalog.createIndex(ci.table(), ci.column(), ci.kind(), ci.unique());
                yield new Result.Ok();
            }
            case Command.DropIndex di -> {
                catalog.dropIndex(di.table(), di.column());
                yield new Result.Ok();
            }

            case Command.Insert ins -> {
                catalog.getTable(ins.table()).insert(ins.row());
                yield new Result.Count(1);
            }
            case Command.Update upd -> {
                Table t = catalog.getTable(upd.table());
                long n = (upd.where() == null)
                        ? t.update(r -> true, upd.patch())
                        : t.update(upd.where(), upd.patch());
                maybeCompact(t);
                yield new Result.Count(n);
            }
            case Command.Delete del -> {
                Table t = catalog.getTable(del.table());
                long n = (del.where() == null)
                        ? t.delete(r -> true)
                        : t.delete(del.where());
                maybeCompact(t);
                yield new Result.Count(n);
            }

            case Command.Select sel -> {
                Table t = catalog.getTable(sel.table());
                List<Map<String, Object>> rows = (sel.where() == null)
                        ? t.selectAll()
                        : t.select(sel.where());
                if (!sel.columns().isEmpty()) {
                    rows = project(rows, sel.columns());
                }
                yield new Result.Rows(rows);
            }
        };
    }

    private static List<Map<String, Object>> project(
            List<Map<String, Object>> rows, List<String> cols) {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> p = new LinkedHashMap<>();
            for (String c : cols) p.put(c, row.get(c));
            out.add(p);
        }
        return out;
    }

    private static void maybeCompact(Table t) throws IOException {
        if (t.needsCompaction()) t.compact();
    }
}