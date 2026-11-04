package query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import query.Ast.*;

public class Parser {
    private final List<Token> tokens;
    private int pos = 0;

    public Parser(List<Token> tokens){
        this.tokens = tokens;
    }

    public static Statement parse(String sql) {
        return new Parser(new Lexer(sql).tokenize()).parseStatement();
    }

    public Statement parseStatement() {
        Statement stmt = switch(peek().type) {
            case CREATE -> parseCreate();
            case INSERT -> parseInsert();
            case DROP -> parseDrop();
            case DELETE -> parseDelete();
            case SELECT -> parseSelect();
            case UPDATE -> parseUpdate();
            default -> throw error("Expected a statement");
        };
        expect(TokenType.EOF);
        return stmt;
    }

    private Statement parseCreate() {
        expect(TokenType.CREATE);
        if (match(TokenType.TABLE)) {
            String name = expectIdentifier();
            expect(TokenType.OPEN_PAREN);
            List<ColumnDef> cols = new ArrayList<>();
            do {
                String colName = expectIdentifier();
                String colType = expectIdentifier(); // e.g. INT, TEXT (lexed as identifiers)
                cols.add(new ColumnDef(colName, colType));
            } while (match(TokenType.COMMA));
            expect(TokenType.CLOSE_PAREN);
            return new CreateTable(name, cols);
        }
        if (match(TokenType.INDEX)) {
            String name = expectIdentifier();
            expect(TokenType.ON);
            String table = expectIdentifier();
            expect(TokenType.OPEN_PAREN);
            String column = expectIdentifier();
            expect(TokenType.CLOSE_PAREN);
            return new CreateIndex(name, table, column);
        }
        throw error("Expected TABLE or INDEX after CREATE");
    }

    private Statement parseDrop() {
        expect(TokenType.DROP);
        if (match(TokenType.TABLE)) return new DropTable(expectIdentifier());
        if (match(TokenType.INDEX)) return new DropIndex(expectIdentifier());
        throw error("Expected TABLE or INDEX after DROP");
    }

    private Statement parseInsert() {
        expect(TokenType.INSERT);
        expect(TokenType.INTO);
        String table = expectIdentifier();

        List<String> columns = new ArrayList<>();
        if (check(TokenType.OPEN_PAREN)) {
            columns = parseIdentifierList();
        }

        expect(TokenType.VALUES);
        expect(TokenType.OPEN_PAREN);
        List<Literal> values = new ArrayList<>();
        do {
            values.add(parseLiteral());
        } while (match(TokenType.COMMA));
        expect(TokenType.CLOSE_PAREN);

        if (!columns.isEmpty() && columns.size() != values.size()) {
            throw new ParseException("Column count (" + columns.size()
                    + ") does not match value count (" + values.size() + ")");
        }
        return new Insert(table, columns, values);
    }

    private Statement parseSelect() {
        expect(TokenType.SELECT);
        List<String> columns = new ArrayList<>();
        if (!match(TokenType.ASTERISK)) {
            do {
                columns.add(expectIdentifier());
            } while (match(TokenType.COMMA));
        }
        expect(TokenType.FROM);
        String table = expectIdentifier();
        return new Select(columns, table, parseOptionalWhere());
    }

    private Statement parseUpdate() {
        expect(TokenType.UPDATE);
        String table = expectIdentifier();
        expect(TokenType.SET);
        Map<String, Literal> assignments = new LinkedHashMap<>();
        do {
            String col = expectIdentifier();
            expect(TokenType.EQUALS);
            if (assignments.put(col, parseLiteral()) != null) {
                throw new ParseException("Column '" + col + "' assigned more than once");
            }
        } while (match(TokenType.COMMA));
        return new Update(table, assignments, parseOptionalWhere());
    }

    private Statement parseDelete() {
        expect(TokenType.DELETE);
        expect(TokenType.FROM);
        String table = expectIdentifier();
        return new Delete(table, parseOptionalWhere());
    }

    // ---------- expressions ----------

    private Expr parseOptionalWhere() {
        return match(TokenType.WHERE) ? parseExpr() : null;
    }

    private Expr parseExpr() {
        Expr left = parseAnd();
        while (match(TokenType.OR)) {
            left = new Logical(TokenType.OR, left, parseAnd());
        }
        return left;
    }

    private Expr parseAnd() {
        Expr left = parseNot();
        while (match(TokenType.AND)) {
            left = new Logical(TokenType.AND, left, parseNot());
        }
        return left;
    }

    private Expr parseNot() {
        if (match(TokenType.NOT)) {
            return new Not(parseNot());
        }
        return parsePredicate();
    }

    private Expr parsePredicate() {
        if (match(TokenType.OPEN_PAREN)) {
            Expr inner = parseExpr();
            expect(TokenType.CLOSE_PAREN);
            return inner;
        }
        Expr left = parseOperand();
        Token opTok = peek();
        switch (opTok.type) {
            case EQUALS, NEQ, LT, GT, LE, GE -> advance();
            default -> throw error("Expected comparison operator");
        }
        Expr right = parseOperand();
        return new Comparison(left, opTok.type, right);
    }

    private Expr parseOperand() {
        if (check(TokenType.IDENTIFIER)) {
            return new Column(advance().value);
        }
        return parseLiteral();
    }

    private Literal parseLiteral() {
        Token t = peek();
        switch (t.type) {
            case NUMBER -> {
                advance();
                try {
                    return new Literal(Long.parseLong(t.value));
                } catch (NumberFormatException e) {
                    throw new ParseException("Number out of range '" + t.value + "' at position " + t.position);
                }
            }
            case STRING -> {
                advance();
                return new Literal(t.value);
            }
            case BOOLEAN -> {
                advance();
                return new Literal(Boolean.parseBoolean(t.value));
            }
            default -> throw error("Expected a literal value");
        }
    }

    private List<String> parseIdentifierList() {
        expect(TokenType.OPEN_PAREN);
        List<String> names = new ArrayList<>();
        do {
            names.add(expectIdentifier());
        } while (match(TokenType.COMMA));
        expect(TokenType.CLOSE_PAREN);
        return names;
    }

    //хелперы
    private Token peek() {
        return tokens.get(pos);
    }

    private Token advance() {
        Token t = tokens.get(pos);
        if (t.type != TokenType.EOF) pos++;
        return t;
    }

    private boolean check(TokenType type) {
        return peek().type == type;
    }

    private boolean match(TokenType type) {
        if (check(type)) {
            advance();
            return true;
        }
        return false;
    }

    private Token expect(TokenType type) {
        if (!check(type)) throw error("Expected " + type);
        return advance();
    }

    private String expectIdentifier() {
        return expect(TokenType.IDENTIFIER).value;
    }

    private ParseException error(String message) {
        Token t = peek();
        String found = t.type == TokenType.EOF ? "end of input" : t.type + " '" + t.value + "'";
        return new ParseException(message + " but found " + found + " at position " + t.position);
    }

}

