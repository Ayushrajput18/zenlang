package zenlang.ast;

import java.util.List;

/**
 * Complete AST for Zen-Lang v3.
 *
 * New in v3:
 *   StructDefNode       – struct with fields, constructor, methods
 *   MemberAccessNode    – obj.field
 *   MethodCallNode      – obj.method(args)
 *   MemberAssignNode    – obj.field = value  /  this.field = value
 *   ThisNode            – the 'this' keyword inside a struct method
 *   MapLiteralNode      – {"key": value, ...}
 *   ForeachNode         – foreach (type var in iterable) { }
 *   SwitchNode          – switch (expr) { case v: { } default: { } }
 *   ConstDeclNode       – const int MAX = 100;
 *   EnumDefNode         – enum Color { RED, GREEN, BLUE }
 *   ImportNode          – import "file.zen";
 */
public final class AST {
    private AST() {}
// Base
public static abstract class ASTNode {}
    public static abstract class ExprNode extends ASTNode {}
// ── DECLARATIONS
/** int x = 5;  /  float pi = 3.14;  /  string name = "Ayush"; */
    public static class TypedVarDeclNode extends ASTNode {
        public final String   type, name;
        public final ExprNode value;
        public TypedVarDeclNode(String type, String name, ExprNode value) {
            this.type = type; this.name = name; this.value = value;
        }
    }

    /** const int MAX = 100; */
    public static class ConstDeclNode extends ASTNode {
        public final String   type, name;
        public final ExprNode value;
        public ConstDeclNode(String type, String name, ExprNode value) {
            this.type = type; this.name = name; this.value = value;
        }
    }

    /** int add(int a, int b) { ... }  – top-level typed function */
    public static class TypedFunctionNode extends ASTNode {
        public static class Param {
            public final String type, name;
            public Param(String type, String name) { this.type = type; this.name = name; }
        }
        public final String        returnType, name;
        public final List<Param>   params;
        public final List<ASTNode> body;
        public TypedFunctionNode(String returnType, String name,
                                  List<Param> params, List<ASTNode> body) {
            this.returnType = returnType; this.name = name;
            this.params = params; this.body = body;
        }
    }
// ── STRUCTS
/**
     * struct Point {
     *     int x;
     *     int y;
     *     Point(int x, int y) { this.x = x; this.y = y; }
     *     float distance() { return sqrt(this.x*this.x + this.y*this.y); }
     * }
     */
    public static class StructDefNode extends ASTNode {
        public final String              name;
        public final List<FieldDeclNode> fields;
        public final ConstructorNode     constructor; // null if no explicit constructor
        public final List<TypedFunctionNode> methods;
        public StructDefNode(String name, List<FieldDeclNode> fields,
                              ConstructorNode constructor, List<TypedFunctionNode> methods) {
            this.name = name; this.fields = fields;
            this.constructor = constructor; this.methods = methods;
        }
    }

    /** A field declaration inside a struct: int x; */
    public static class FieldDeclNode extends ASTNode {
        public final String   type, name;
        public final ExprNode defaultValue; // null if no default
        public FieldDeclNode(String type, String name, ExprNode defaultValue) {
            this.type = type; this.name = name; this.defaultValue = defaultValue;
        }
    }

    /** StructName(params) { body } */
    public static class ConstructorNode extends ASTNode {
        public final String              structName;
        public final List<TypedFunctionNode.Param> params;
        public final List<ASTNode>       body;
        public ConstructorNode(String structName,
                                List<TypedFunctionNode.Param> params,
                                List<ASTNode> body) {
            this.structName = structName; this.params = params; this.body = body;
        }
    }

    /** obj.field  */
    public static class MemberAccessNode extends ExprNode {
        public final ExprNode object;
        public final String   field;
        public MemberAccessNode(ExprNode object, String field) {
            this.object = object; this.field = field;
        }
    }

    /** obj.method(args) */
    public static class MethodCallNode extends ExprNode {
        public final ExprNode       object;
        public final String         method;
        public final List<ExprNode> args;
        public MethodCallNode(ExprNode object, String method, List<ExprNode> args) {
            this.object = object; this.method = method; this.args = args;
        }
    }

    /** obj.field = value   OR   this.field = value */
    public static class MemberAssignNode extends ASTNode {
        public final ExprNode object;
        public final String   field;
        public final ExprNode value;
        public MemberAssignNode(ExprNode object, String field, ExprNode value) {
            this.object = object; this.field = field; this.value = value;
        }
    }

    /** The 'this' keyword inside a struct method */
    public static class ThisNode extends ExprNode {}
// ── ASSIGNMENTS
/** x = expr; */
    public static class AssignNode extends ASTNode {
        public final String name; public final ExprNode value;
        public AssignNode(String name, ExprNode value) { this.name=name; this.value=value; }
    }

    /** x += expr;  x -= expr;  etc. */
    public static class CompoundAssignNode extends ASTNode {
        public final String name, op; public final ExprNode value;
        public CompoundAssignNode(String name, String op, ExprNode value) {
            this.name=name; this.op=op; this.value=value;
        }
    }

    /** arr[i] = expr;   OR   map["key"] = expr; */
    public static class ArrayAssignNode extends ASTNode {
        public final ExprNode array, index, value;
        public ArrayAssignNode(ExprNode array, ExprNode index, ExprNode value) {
            this.array=array; this.index=index; this.value=value;
        }
    }

    /** i++;   i--; */
    public static class IncrDecrNode extends ASTNode {
        public final String name, op;
        public IncrDecrNode(String name, String op) { this.name=name; this.op=op; }
    }
// ── CONTROL FLOW
/** if (cond) { } else if (cond) { } else { } */
    public static class IfNode extends ASTNode {
        public final ExprNode condition;
        public final List<ASTNode> thenBranch, elseBranch;
        public IfNode(ExprNode cond, List<ASTNode> then, List<ASTNode> els) {
            condition=cond; thenBranch=then; elseBranch=els;
        }
    }

    /** while (cond) { } */
    public static class WhileNode extends ASTNode {
        public final ExprNode condition; public final List<ASTNode> body;
        public WhileNode(ExprNode c, List<ASTNode> b) { condition=c; body=b; }
    }

    /** for (int i = 0; i < 10; i++) { } */
    public static class ForCStyleNode extends ASTNode {
        public final ASTNode init; public final ExprNode condition;
        public final ASTNode update; public final List<ASTNode> body;
        public ForCStyleNode(ASTNode i, ExprNode c, ASTNode u, List<ASTNode> b) {
            init=i; condition=c; update=u; body=b;
        }
    }

    /** for (int i = 1 to 10 step 2) { } */
    public static class ForRangeNode extends ASTNode {
        public final String varType, varName;
        public final ExprNode start, end, step; // step may be null
        public final List<ASTNode> body;
        public ForRangeNode(String vt, String vn, ExprNode s, ExprNode e,
                             ExprNode step, List<ASTNode> b) {
            varType=vt; varName=vn; start=s; end=e; this.step=step; body=b;
        }
    }

    /**
     * foreach (int n in numbers) { }
     * foreach (string key in myMap) { }
     */
    public static class ForeachNode extends ASTNode {
        public final String    varType, varName;
        public final ExprNode  iterable;
        public final List<ASTNode> body;
        public ForeachNode(String vt, String vn, ExprNode it, List<ASTNode> b) {
            varType=vt; varName=vn; iterable=it; body=b;
        }
    }

    /**
     * switch (expr) {
     *     case 1: { ... }
     *     case 2: { ... }
     *     default: { ... }
     * }
     */
    public static class SwitchNode extends ASTNode {
        public final ExprNode      expr;
        public final List<SwitchCase> cases;
        public final List<ASTNode>    defaultBranch; // empty if no default
        public SwitchNode(ExprNode e, List<SwitchCase> c, List<ASTNode> d) {
            expr=e; cases=c; defaultBranch=d;
        }
        public static class SwitchCase {
            public final ExprNode value; public final List<ASTNode> body;
            public SwitchCase(ExprNode v, List<ASTNode> b) { value=v; body=b; }
        }
    }

    /** return expr; */
    public static class ReturnNode extends ASTNode {
        public final ExprNode value;
        public ReturnNode(ExprNode v) { value=v; }
    }

    /** break; */
    public static class BreakNode extends ASTNode {}

    /** continue; */
    public static class ContinueNode extends ASTNode {}

    /** Expression as statement: println("hi"); */
    public static class ExprStatementNode extends ASTNode {
        public final ExprNode expr;
        public ExprStatementNode(ExprNode e) { expr=e; }
    }
// ── ENUM & IMPORT ─────────────────────────────────────────────────────────
/**
     * enum Direction { NORTH, SOUTH, EAST, WEST }
     * Access: Direction.NORTH
     */
    public static class EnumDefNode extends ASTNode {
        public final String       name;
        public final List<String> values; // ordered value names
        public EnumDefNode(String name, List<String> values) {
            this.name=name; this.values=values;
        }
    }

    /** import "utils.zen"; */
    public static class ImportNode extends ASTNode {
        public final String path;
        public ImportNode(String path) { this.path=path; }
    }
// ── EXPRESSIONS ───────────────────────────────────────────────────────────
/** left op right */
    public static class BinaryExprNode extends ExprNode {
        public final String op; public final ExprNode left, right;
        public BinaryExprNode(String op, ExprNode l, ExprNode r) { this.op=op; left=l; right=r; }
    }

    /** op operand  (!, -, ++, --) */
    public static class UnaryExprNode extends ExprNode {
        public final String op; public final ExprNode operand;
        public UnaryExprNode(String op, ExprNode e) { this.op=op; operand=e; }
    }

    /** Variable reference */
    public static class IdentifierNode extends ExprNode {
        public final String name;
        public IdentifierNode(String name) { this.name=name; }
    }

    /** Integer / decimal literal */
    public static class NumberNode extends ExprNode {
        public final String value;
        public NumberNode(String value) { this.value=value; }
    }

    /** String literal */
    public static class StringNode extends ExprNode {
        public final String value;
        public StringNode(String value) { this.value=value; }
    }

    /** null literal */
    public static class NullNode extends ExprNode {}

    /** [e1, e2, e3] */
    public static class ArrayNode extends ExprNode {
        public final List<ExprNode> elements;
        public ArrayNode(List<ExprNode> e) { elements=e; }
    }

    /**
     * {"key1": val1, "key2": val2}
     * Keys are any expression (typically strings or numbers).
     */
    public static class MapLiteralNode extends ExprNode {
        public static class Entry {
            public final ExprNode key, value;
            public Entry(ExprNode k, ExprNode v) { key=k; value=v; }
        }
        public final List<Entry> entries;
        public MapLiteralNode(List<Entry> e) { entries=e; }
    }

    /** array[index]  OR  map["key"] */
    public static class IndexNode extends ExprNode {
        public final ExprNode array, index;
        public IndexNode(ExprNode a, ExprNode i) { array=a; index=i; }
    }

    /** func(arg1, arg2) */
    public static class CallNode extends ExprNode {
        public final String func; public final List<ExprNode> args;
        public CallNode(String f, List<ExprNode> a) { func=f; args=a; }
    }
}
