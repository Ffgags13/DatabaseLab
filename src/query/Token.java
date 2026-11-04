package query;

public class Token {
    public final TokenType type;
    public final String value;
    public int position;

    public Token(TokenType type, String value) {
        this.type = type;
        this.value = value;
    }

    @Override
    public String toString() {
        return type + "('" + value + "')@" + position;
    }
}
