package zenlang.lexer;

public enum TokenType {
    IDENTIFIER,
    NUMBER,
    DECIMAL,
    STRING,
    KEYWORD,
    OPERATOR,
    HEADER,
    LPAREN,
    RPAREN,
    LBRACKET,
    RBRACKET,
    LBRACE,
    RBRACE,
    COMMA,
    ASSIGN,
    DOT,        // .  (member access)
    COLON,      // :  (map literal, switch case)
    END_OF_FILE,
    UNKNOWN
}
