package zenlang.parser;

import zenlang.ast.AST.*;
import zenlang.lexer.Token;
import zenlang.lexer.TokenType;
import zenlang.lexer.Tokens;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for Zen-Lang v3.
 *
 * New constructs handled:
 *   struct definitions, constructors, methods
 *   obj.field   obj.method(args)   obj.field = value
 *   this.field  this.method(args)
 *   map literals {"k": v}    map["k"]    map["k"] = v
 *   foreach (type var in iterable) { }
 *   switch (expr) { case val: { } default: { } }
 *   const type name = value;
 *   enum Name { VAL1, VAL2 }
 *   import "file.zen";
 */
public class Parser {

    private final List<Token> tokens;
    private int pos;

    public Parser(List<Token> tokens) { this.tokens = tokens; this.pos = 0; }
// Entry
public List<ASTNode> parse() {
        List<ASTNode> nodes = new ArrayList<>();
        while (!isAtEnd()) {
            ASTNode s = parseStatement();
            if (s != null) nodes.add(s);
            else advance();
        }
        return nodes;
    }
// Statement dispatcher
ASTNode parseStatement() {

        // ── import "file.zen"; ────────────────────────────────────────────────
        if (isKw("import")) return parseImport();

        // ── enum Name { ... } ─────────────────────────────────────────────────
        if (isKw("enum")) return parseEnum();

        // ── struct Name { ... } ───────────────────────────────────────────────
        if (isKw("struct")) return parseStruct();

        // ── const type name = value; ─────────────────────────────────────────
        if (isKw("const")) return parseConst();

        // ── Typed declaration OR typed function ──────────────────────────────
        if (isTypeKw()) {
            // TYPE IDENT '(' → function
            if (peek(1).type == TokenType.IDENTIFIER && peek(2).type == TokenType.LPAREN)
                return parseTypedFunction();
            return parseTypedVarDecl();
        }

        // ── return / break / continue ────────────────────────────────────────
        if (isKw("return"))   return parseReturn();
        if (isKw("break"))    { advance(); expectSemi("after break");    return new BreakNode(); }
        if (isKw("continue")) { advance(); expectSemi("after continue"); return new ContinueNode(); }

        // ── control flow ─────────────────────────────────────────────────────
        if (isKw("if"))      return parseIf();
        if (isKw("while"))   return parseWhile();
        if (isKw("for"))     return parseFor();
        if (isKw("foreach")) return parseForeach();
        if (isKw("switch"))  return parseSwitch();

        // ── identifier-first statements: i++  i+=  obj.f=  arr[i]= ─────────
        if (peek().type == TokenType.IDENTIFIER) {
            String name = peek().value;

            // i++  i--
            if (peek(1).type == TokenType.OPERATOR &&
               (peek(1).value.equals("++") || peek(1).value.equals("--"))) {
                advance(); String op = advance().value;
                expectSemi("after " + op);
                return new IncrDecrNode(name, op);
            }

            // i +=  i -=  i *=  i /=
            if (peek(1).type == TokenType.OPERATOR && isCompound(peek(1).value)) {
                advance(); String op = advance().value;
                ExprNode v = parseExpression();
                expectSemi("after compound assignment");
                return new CompoundAssignNode(name, op, v);
            }
        }

        // ── Expression statement (handles all other cases including assignments)
        ExprNode expr = parseExpression();

        // Check for assignment after expression
        if (peek().type == TokenType.ASSIGN) {
            advance();
            ExprNode val = parseExpression();
            expectSemi("after assignment");
            if (expr instanceof IdentifierNode id)    return new AssignNode(id.name, val);
            if (expr instanceof IndexNode      idx)   return new ArrayAssignNode(idx.array, idx.index, val);
            if (expr instanceof MemberAccessNode ma)  return new MemberAssignNode(ma.object, ma.field, val);
            throw err("Invalid assignment target", peek().line);
        }

        expectSemi("after statement");
        return new ExprStatementNode(expr);
    }
// Typed declarations
private ASTNode parseTypedVarDecl() {
        String type = advance().value;
        if (peek().type != TokenType.IDENTIFIER) throw err("Expected variable name after '" + type + "'", peek().line);
        String name = advance().value;
        ExprNode value = null;
        if (peek().type == TokenType.ASSIGN) {
            advance();
            value = parseExpression();
        }
        expectSemi("after variable declaration");
        return new TypedVarDeclNode(type, name, value);
    }

    private ASTNode parseConst() {
        advance(); // const
        if (!isTypeKw()) throw err("Expected type after 'const'", peek().line);
        String type = advance().value;
        String name = advance().value;
        expect(TokenType.ASSIGN, "Expected '=' after const name");
        ExprNode value = parseExpression();
        expectSemi("after const declaration");
        return new ConstDeclNode(type, name, value);
    }

    private ASTNode parseTypedFunction() {
        String returnType = advance().value;
        String name       = advance().value;
        expect(TokenType.LPAREN, "Expected '(' after function name '" + name + "'");
        List<TypedFunctionNode.Param> params = parseParamList();
        expect(TokenType.RPAREN, "Expected ')' after parameters");
        List<ASTNode> body = parseBlock("function body");
        return new TypedFunctionNode(returnType, name, params, body);
    }
// Struct
/**
     * struct Name {
     *     int field;
     *     float field2 = 0.0;
     *     Name(int field, float field2) { this.field = field; this.field2 = field2; }
     *     returnType methodName(params) { ... }
     * }
     */
    private ASTNode parseStruct() {
        advance(); // struct
        if (peek().type != TokenType.IDENTIFIER) throw err("Expected struct name", peek().line);
        String structName = advance().value;
        expect(TokenType.LBRACE, "Expected '{' after struct name");

        List<FieldDeclNode>      fields      = new ArrayList<>();
        ConstructorNode          constructor = null;
        List<TypedFunctionNode>  methods     = new ArrayList<>();

        while (!isAtEnd() && peek().type != TokenType.RBRACE) {
            // Constructor: StructName(params) { }
            if (peek().type == TokenType.IDENTIFIER && peek().value.equals(structName)
                && peek(1).type == TokenType.LPAREN) {
                advance(); // consume struct name
                expect(TokenType.LPAREN, "Expected '(' in constructor");
                List<TypedFunctionNode.Param> params = parseParamList();
                expect(TokenType.RPAREN, "Expected ')' after constructor params");
                List<ASTNode> body = parseBlock("constructor body");
                constructor = new ConstructorNode(structName, params, body);
                continue;
            }
            // Method or field: TYPE name ...
            if (isTypeKw() || peek().type == TokenType.KEYWORD) {
                String type = advance().value;
                if (peek().type != TokenType.IDENTIFIER) throw err("Expected name after type in struct", peek().line);
                String memberName = advance().value;

                if (peek().type == TokenType.LPAREN) {
                    // Method definition
                    advance();
                    List<TypedFunctionNode.Param> params = parseParamList();
                    expect(TokenType.RPAREN, "Expected ')' after method params");
                    List<ASTNode> body = parseBlock("method body");
                    methods.add(new TypedFunctionNode(type, memberName, params, body));
                } else {
                    // Field declaration
                    ExprNode defVal = null;
                    if (peek().type == TokenType.ASSIGN) { advance(); defVal = parseExpression(); }
                    expectSemi("after struct field declaration");
                    fields.add(new FieldDeclNode(type, memberName, defVal));
                }
                continue;
            }
            advance(); // skip unknown
        }
        expect(TokenType.RBRACE, "Expected '}' to close struct '" + structName + "'");
        return new StructDefNode(structName, fields, constructor, methods);
    }
// Enum
/** enum Direction { NORTH, SOUTH, EAST, WEST } */
    private ASTNode parseEnum() {
        advance(); // enum
        if (peek().type != TokenType.IDENTIFIER) throw err("Expected enum name", peek().line);
        String name = advance().value;
        expect(TokenType.LBRACE, "Expected '{' after enum name");
        List<String> values = new ArrayList<>();
        while (!isAtEnd() && peek().type != TokenType.RBRACE) {
            if (peek().type != TokenType.IDENTIFIER) throw err("Expected enum value name", peek().line);
            values.add(advance().value);
            if (peek().type == TokenType.COMMA) advance();
        }
        expect(TokenType.RBRACE, "Expected '}' to close enum");
        return new EnumDefNode(name, values);
    }
// Import
/** import "utils.zen"; */
    private ASTNode parseImport() {
        advance(); // import
        if (peek().type != TokenType.STRING) throw err("Expected file path string after 'import'", peek().line);
        String path = advance().value;
        expectSemi("after import");
        return new ImportNode(path);
    }
// Control flow
private ASTNode parseIf() {
        advance();
        expect(TokenType.LPAREN, "Expected '(' after 'if'");
        ExprNode cond = parseExpression();
        expect(TokenType.RPAREN, "Expected ')' after if condition");
        List<ASTNode> then = parseBlock("if");
        List<ASTNode> els  = new ArrayList<>();
        if (isKw("else")) {
            advance();
            if (isKw("if")) els.add(parseIf());
            else            els = parseBlock("else");
        }
        return new IfNode(cond, then, els);
    }

    private ASTNode parseWhile() {
        advance();
        expect(TokenType.LPAREN, "Expected '(' after 'while'");
        ExprNode cond = parseExpression();
        expect(TokenType.RPAREN, "Expected ')' after while condition");
        return new WhileNode(cond, parseBlock("while"));
    }

    private ASTNode parseFor() {
        advance();
        expect(TokenType.LPAREN, "Expected '(' after 'for'");

        String type = "";
        if (isTypeKw()) type = advance().value;
        if (peek().type != TokenType.IDENTIFIER) throw err("Expected loop variable", peek().line);
        String var = advance().value;
        expect(TokenType.ASSIGN, "Expected '=' after loop variable");
        ExprNode start = parseExpression();

        // Discriminate: ';' → C-style, 'to' → range
        if (peek().type == TokenType.OPERATOR && peek().value.equals(";")) {
            advance();
            ExprNode cond = parseExpression();
            expectOp(";", "Expected ';' after for-condition");
            ASTNode update = parseForUpdate();
            expect(TokenType.RPAREN, "Expected ')' after for-update");
            return new ForCStyleNode(new TypedVarDeclNode(type, var, start),
                                     cond, update, parseBlock("for"));
        } else if (isKw("to")) {
            advance();
            ExprNode end  = parseExpression();
            ExprNode step = isKw("step") ? (advance() != null ? parseExpression() : null) : null;
            expect(TokenType.RPAREN, "Expected ')' after range");
            return new ForRangeNode(type, var, start, end, step, parseBlock("for"));
        }
        throw err("Expected ';' (C-style) or 'to' (range) in for", peek().line);
    }

    private ASTNode parseForUpdate() {
        if (peek().type != TokenType.IDENTIFIER) throw err("Expected update expression", peek().line);
        String name = advance().value;
        String op   = peek().value;
        if (peek().type == TokenType.OPERATOR) {
            if (op.equals("++") || op.equals("--")) { advance(); return new IncrDecrNode(name, op); }
            if (isCompound(op))                      { advance(); return new CompoundAssignNode(name, op, parseExpression()); }
        }
        if (peek().type == TokenType.ASSIGN) { advance(); return new AssignNode(name, parseExpression()); }
        throw err("Expected update expression in for", peek().line);
    }

    /** foreach (type var in iterable) { } */
    private ASTNode parseForeach() {
        advance();
        expect(TokenType.LPAREN, "Expected '(' after 'foreach'");
        String type = isTypeKw() ? advance().value : "auto";
        if (peek().type != TokenType.IDENTIFIER) throw err("Expected variable name in foreach", peek().line);
        String var = advance().value;
        if (!isKw("in")) throw err("Expected 'in' after foreach variable", peek().line);
        advance();
        ExprNode iterable = parseExpression();
        expect(TokenType.RPAREN, "Expected ')' after foreach iterable");
        return new ForeachNode(type, var, iterable, parseBlock("foreach"));
    }

    /**
     * switch (expr) {
     *     case val: { ... }
     *     default: { ... }
     * }
     */
    private ASTNode parseSwitch() {
        advance();
        expect(TokenType.LPAREN, "Expected '(' after 'switch'");
        ExprNode expr = parseExpression();
        expect(TokenType.RPAREN, "Expected ')' after switch expression");
        expect(TokenType.LBRACE, "Expected '{' after switch(...)");

        List<SwitchNode.SwitchCase> cases = new ArrayList<>();
        List<ASTNode> defaultBranch = new ArrayList<>();

        while (!isAtEnd() && peek().type != TokenType.RBRACE) {
            if (isKw("case")) {
                advance();
                ExprNode caseVal = parseExpression();
                expect(TokenType.COLON, "Expected ':' after case value");
                List<ASTNode> body = parseBlock("case");
                cases.add(new SwitchNode.SwitchCase(caseVal, body));
            } else if (isKw("default")) {
                advance();
                expect(TokenType.COLON, "Expected ':' after 'default'");
                defaultBranch = parseBlock("default");
            } else {
                advance();
            }
        }
        expect(TokenType.RBRACE, "Expected '}' to close switch");
        return new SwitchNode(expr, cases, defaultBranch);
    }

    private ASTNode parseReturn() {
        advance();
        ExprNode v = parseExpression();
        expectSemi("after return");
        return new ReturnNode(v);
    }
// Expressions
ExprNode parseExpression() { return parseBinary(0); }

    private ExprNode parseBinary(int minPrec) {
        ExprNode left = parseUnary();
        while (peek().type == TokenType.OPERATOR) {
            int prec = prec(peek().value);
            if (prec < minPrec || prec < 0) break;
            String op = advance().value;
            left = new BinaryExprNode(op, left, parseBinary(prec + 1));
        }
        return left;
    }

    private ExprNode parseUnary() {
        if (peek().type == TokenType.OPERATOR) {
            String op = peek().value;
            if (op.equals("!"))  { advance(); return new UnaryExprNode("!", parseUnary()); }
            if (op.equals("-"))  { advance(); return new UnaryExprNode("-", parseUnary()); }
            if (op.equals("++") || op.equals("--")) { advance(); return new UnaryExprNode(op, parseUnary()); }
        }
        return parsePostfix();
    }

    /** Parse primary then chain: [index], .field, .method(args) */
    private ExprNode parsePostfix() {
        ExprNode expr = parsePrimary();
        while (true) {
            if (peek().type == TokenType.LBRACKET) {
                advance();
                ExprNode idx = parseExpression();
                expect(TokenType.RBRACKET, "Expected ']' after index");
                expr = new IndexNode(expr, idx);
            } else if (peek().type == TokenType.DOT) {
                advance();
                if (peek().type != TokenType.IDENTIFIER)
                    throw err("Expected field or method name after '.'", peek().line);
                String member = advance().value;
                if (peek().type == TokenType.LPAREN) {
                    // method call
                    advance();
                    List<ExprNode> args = parseArgList();
                    expect(TokenType.RPAREN, "Expected ')' after method arguments");
                    expr = new MethodCallNode(expr, member, args);
                } else {
                    expr = new MemberAccessNode(expr, member);
                }
            } else {
                break;
            }
        }
        return expr;
    }

    private ExprNode parsePrimary() {
        // Map literal  {"key": val, ...}
        if (peek().type == TokenType.LBRACE) {
            advance();
            List<MapLiteralNode.Entry> entries = new ArrayList<>();
            if (peek().type != TokenType.RBRACE) {
                do {
                    ExprNode key = parseExpression();
                    expect(TokenType.COLON, "Expected ':' in map literal");
                    ExprNode val = parseExpression();
                    entries.add(new MapLiteralNode.Entry(key, val));
                    if (peek().type == TokenType.COMMA) advance(); else break;
                } while (true);
            }
            expect(TokenType.RBRACE, "Expected '}' to close map literal");
            return new MapLiteralNode(entries);
        }

        // Array literal  [e1, e2, ...]
        if (peek().type == TokenType.LBRACKET) {
            advance();
            List<ExprNode> elems = new ArrayList<>();
            if (peek().type != TokenType.RBRACKET) {
                do {
                    elems.add(parseExpression());
                    if (peek().type == TokenType.COMMA) advance(); else break;
                } while (true);
            }
            expect(TokenType.RBRACKET, "Expected ']' in array literal");
            return new ArrayNode(elems);
        }

        // Parenthesised expression
        if (peek().type == TokenType.LPAREN) {
            advance();
            ExprNode e = parseExpression();
            expect(TokenType.RPAREN, "Expected ')' after expression");
            return e;
        }

        // 'this' keyword
        if (isKw("this")) { advance(); return new ThisNode(); }

        // Boolean / null literals
        if (isKw("true"))  { advance(); return new NumberNode("1"); }
        if (isKw("false")) { advance(); return new NumberNode("0"); }
        if (isKw("null"))  { advance(); return new NullNode(); }

        // Numbers
        if (peek().type == TokenType.NUMBER || peek().type == TokenType.DECIMAL)
            return new NumberNode(advance().value);

        // Strings
        if (peek().type == TokenType.STRING)
            return new StringNode(advance().value);

        // Identifier: variable, function call, or struct instantiation
        if (peek().type == TokenType.IDENTIFIER) {
            String name = advance().value;
            if (peek().type == TokenType.LPAREN) {
                advance();
                List<ExprNode> args = parseArgList();
                expect(TokenType.RPAREN, "Expected ')' after arguments to '" + name + "'");
                return new CallNode(name, args);
            }
            return new IdentifierNode(name);
        }

        throw err("Unexpected token '" + peek().value + "'", peek().line);
    }
// Helpers
private List<ASTNode> parseBlock(String ctx) {
        expect(TokenType.LBRACE, "Expected '{' to start " + ctx);
        List<ASTNode> stmts = new ArrayList<>();
        while (!isAtEnd() && peek().type != TokenType.RBRACE) {
            ASTNode s = parseStatement();
            if (s != null) stmts.add(s); else advance();
        }
        expect(TokenType.RBRACE, "Expected '}' to end " + ctx);
        return stmts;
    }

    private List<TypedFunctionNode.Param> parseParamList() {
        List<TypedFunctionNode.Param> params = new ArrayList<>();
        if (peek().type == TokenType.RPAREN) return params;
        do {
            if (!isTypeKw()) throw err("Expected parameter type", peek().line);
            String pType = advance().value;
            if (peek().type != TokenType.IDENTIFIER) throw err("Expected parameter name", peek().line);
            String pName = advance().value;
            params.add(new TypedFunctionNode.Param(pType, pName));
            if (peek().type == TokenType.COMMA) advance(); else break;
        } while (true);
        return params;
    }

    private List<ExprNode> parseArgList() {
        List<ExprNode> args = new ArrayList<>();
        if (peek().type == TokenType.RPAREN) return args;
        do {
            args.add(parseExpression());
            if (peek().type == TokenType.COMMA) advance(); else break;
        } while (true);
        return args;
    }

    private Token peek(int offset) {
        int idx = pos + offset;
        return idx < tokens.size() ? tokens.get(idx) : tokens.get(tokens.size()-1);
    }
    private Token peek()    { return peek(0); }
    private Token advance() { if (!isAtEnd()) pos++; return peek(-1); }
    private boolean isAtEnd() { return pos >= tokens.size() || peek().type == TokenType.END_OF_FILE; }
    private boolean isKw(String kw) { return peek().type == TokenType.KEYWORD && peek().value.equals(kw); }
    private boolean isTypeKw()      { return peek().type == TokenType.KEYWORD && Tokens.isTypeKeyword(peek().value); }

    private void expect(TokenType type, String msg) {
        if (peek().type != type)
            throw err(msg + " (got '" + peek().value + "')", peek().line);
        advance();
    }
    private void expectSemi(String ctx)  {
        if (peek().type == TokenType.OPERATOR && peek().value.equals(";")) { advance(); return; }
        throw err("Expected ';' " + ctx + " (got '" + peek().value + "')", peek().line);
    }
    private void expectOp(String op, String msg) {
        if (peek().type == TokenType.OPERATOR && peek().value.equals(op)) { advance(); return; }
        throw err(msg + " (got '" + peek().value + "')", peek().line);
    }

    private static boolean isCompound(String op) {
        return op.equals("+=") || op.equals("-=") || op.equals("*=") || op.equals("/=");
    }

    private static int prec(String op) {
        return switch (op) {
            case "||"                  -> 1;
            case "&&"                  -> 2;
            case "==","!="             -> 3;
            case "<",">","<=",">="     -> 4;
            case "+","-"               -> 5;
            case "*","/","%"           -> 6;
            default                    -> -1;
        };
    }

    private RuntimeException err(String msg, int line) {
        return new RuntimeException("[Parse Error] " + msg + " at line " + line);
    }
}
