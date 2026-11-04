package query;

import java.util.List;
import java.util.Map;

public final class Ast {
    private Ast(){}

    public interface Statement {}
    public interface Expr {}

    public record ColumnDef(String name, String type){}

    public record CreateTable(String name, List<ColumnDef> columns) implements Statement {}
    public record CreateIndex(String name, String table, String column) implements Statement {}
    public record DropTable(String name) implements Statement {}
    public record DropIndex(String name) implements Statement {}

    public record Insert(String table, List<String> columns, List<Literal> values) implements Statement {}

    public record Select(List<String> columns, String table, Expr where) implements Statement {}

    public record Update(String table, Map<String, Literal> assignments, Expr where) implements Statement {}

    public record Delete(String table, Expr where) implements Statement {}

    public record Literal(Object value) implements Expr {}
    public record Column(String name) implements Expr {}

    public record Comparison(Expr left, TokenType op, Expr right) implements Expr {}

    public record Logical(TokenType op, Expr left, Expr right) implements Expr {}
    public record Not(Expr operand) implements Expr {}
}
