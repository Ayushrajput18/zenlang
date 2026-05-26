package zenlang.interpreter;

import zenlang.ast.AST.*;
import zenlang.lexer.Lexer;
import zenlang.lexer.Token;
import zenlang.parser.Parser;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Tree-walking interpreter for Zen-Lang v3.
 *
 * New features:
 *   structs / objects / methods / constructors
 *   map / dictionary type
 *   foreach loop  (arrays and maps)
 *   switch statement
 *   const declarations
 *   enum types
 *   import system
 *   file I/O built-ins
 */
public class Interpreter {

    // ── Interpreter-level registries ────────────────────────────────────────

    /** Struct definitions keyed by name */
    private final Map<String, StructDef> structs   = new LinkedHashMap<>();
    /** Enum definitions keyed by name */
    private final Map<String, EnumDef>   enums     = new LinkedHashMap<>();
    /** User-defined functions keyed by name */
    private final Map<String, TypedFunctionNode> functions = new LinkedHashMap<>();
    /** Constant variable names (immutable after declaration) */
    private final Set<String>            constants = new HashSet<>();
    /** Already-imported file paths (prevent re-importing) */
    private final Set<String>            imported  = new HashSet<>();

    // ── Variable scope ────────────────────────────────────────────────────────

    private Map<String, Value>               variables = new LinkedHashMap<>();
    private final List<Map<String, Value>>   callStack = new ArrayList<>();

    // ── Control flow signals ──────────────────────────────────────────────────

    private boolean hasReturn = false, hasBreak = false, hasContinue = false;
    private Value   returnValue = null;

    // ── Shared resources ──────────────────────────────────────────────────────

    private final Scanner stdinScanner = new Scanner(System.in);
    /** Base path for resolving relative imports */
    private String baseDir = ".";

    // ── Internal struct definition record ────────────────────────────────────

    private static class StructDef {
        final String name;
        final List<FieldDeclNode>       fields;
        final ConstructorNode           constructor;  // may be null
        final Map<String, TypedFunctionNode> methods;
        StructDef(String name, List<FieldDeclNode> fields,
                  ConstructorNode constructor, List<TypedFunctionNode> methods) {
            this.name = name; this.fields = fields; this.constructor = constructor;
            this.methods = new LinkedHashMap<>();
            for (TypedFunctionNode m : methods) this.methods.put(m.name, m);
        }
    }

    /** Internal enum definition record */
    private static class EnumDef {
        final String name;
        final List<String>        values;
        final Map<String,Integer> ordinals;
        EnumDef(String name, List<String> values) {
            this.name = name; this.values = values;
            ordinals = new LinkedHashMap<>();
            for (int i = 0; i < values.size(); i++) ordinals.put(values.get(i), i);
        }
    }
// Public entry points
public Interpreter() {}

    /** Set the base directory used to resolve relative import paths. */
    public void setBaseDir(String dir) { this.baseDir = dir; }

    /** Execute a full parsed program. */
    public void interpret(List<ASTNode> ast) {
        // First pass: register all top-level definitions
        for (ASTNode node : ast) registerDefinition(node);
        // Second pass: execute statements
        for (ASTNode node : ast) {
            if (!isDefinition(node)) { exec(node); if (hasReturn) break; }
        }
    }

    private void registerDefinition(ASTNode node) {
        if (node instanceof TypedFunctionNode fn) functions.put(fn.name, fn);
        if (node instanceof StructDefNode sd)      registerStruct(sd);
        if (node instanceof EnumDefNode   ed)      registerEnum(ed);
    }

    private boolean isDefinition(ASTNode node) {
        return node instanceof TypedFunctionNode ||
               node instanceof StructDefNode     ||
               node instanceof EnumDefNode;
    }
// Scope
private void pushScope() { callStack.add(new LinkedHashMap<>(variables)); }
    private void popScope()  { variables = callStack.remove(callStack.size()-1); }

    private void setVar(String name, Value v) {
        if (constants.contains(name))
            throw new RuntimeException("[Runtime Error] Cannot reassign constant '" + name + "'");
        variables.put(name, v);
    }
    private Value getVar(String name) {
        Value v = variables.get(name);
        if (v == null) throw new RuntimeException("[Runtime Error] Undefined variable: '" + name + "'");
        return v;
    }
// Statement executor
private void exec(ASTNode node) {
        if (hasReturn || hasBreak || hasContinue) return;

        // ── import ────────────────────────────────────────────────────────────
        if (node instanceof ImportNode imp) { execImport(imp); return; }

        // ── enum / struct definitions (already registered in first pass) ─────
        if (node instanceof EnumDefNode || node instanceof StructDefNode ||
            node instanceof TypedFunctionNode) return;

        // ── typed variable declaration ────────────────────────────────────────
        if (node instanceof TypedVarDeclNode vd) {
            variables.put(vd.name, vd.value != null ? eval(vd.value) : Value.NULL);
            return;
        }

        // ── const declaration ─────────────────────────────────────────────────
        if (node instanceof ConstDeclNode cd) {
            variables.put(cd.name, eval(cd.value));
            constants.add(cd.name);
            return;
        }

        // ── plain assignment ──────────────────────────────────────────────────
        if (node instanceof AssignNode an) {
            setVar(an.name, eval(an.value)); return;
        }

        // ── compound assignment ───────────────────────────────────────────────
        if (node instanceof CompoundAssignNode ca) {
            setVar(ca.name, applyCompound(ca.op, getVar(ca.name), eval(ca.value), ca.name));
            return;
        }

        // ── array / map element assignment ────────────────────────────────────
        if (node instanceof ArrayAssignNode aa) {
            Value container = eval(aa.array);
            Value idxVal    = eval(aa.index);
            Value newVal    = eval(aa.value);
            if (container.isArray()) {
                container.asArray().set(toIdx(idxVal, container.asArray().size()), newVal);
            } else if (container.isMap()) {
                container.asMap().put(idxVal.display(), newVal);
            } else {
                throw new RuntimeException("[Runtime Error] Cannot index into " + container.kind());
            }
            return;
        }

        // ── member assignment: obj.field = value  /  this.field = value ──────
        if (node instanceof MemberAssignNode ma) {
            Value obj = eval(ma.object);
            if (!obj.isStruct()) throw new RuntimeException("[Runtime Error] Cannot access fields on non-struct value");
            obj.asStruct().fields.put(ma.field, eval(ma.value));
            return;
        }

        // ── i++  i-- ──────────────────────────────────────────────────────────
        if (node instanceof IncrDecrNode id) {
            double v = getVar(id.name).asNumber();
            setVar(id.name, Value.ofNumber(id.op.equals("++") ? v+1 : v-1));
            return;
        }

        // ── expression statement ──────────────────────────────────────────────
        if (node instanceof ExprStatementNode es) { eval(es.expr); return; }

        // ── if / else if / else ───────────────────────────────────────────────
        if (node instanceof IfNode ifN) {
            List<ASTNode> branch = eval(ifN.condition).isTruthy() ? ifN.thenBranch : ifN.elseBranch;
            for (ASTNode s : branch) { exec(s); if (signal()) return; }
            return;
        }

        // ── while ─────────────────────────────────────────────────────────────
        if (node instanceof WhileNode wn) {
            while (eval(wn.condition).isTruthy()) {
                for (ASTNode s : wn.body) {
                    exec(s);
                    if (hasReturn) return;
                    if (hasBreak)  { hasBreak = false; return; }
                    if (hasContinue) { hasContinue = false; break; }
                }
            }
            return;
        }

        // ── C-style for ───────────────────────────────────────────────────────
        if (node instanceof ForCStyleNode fc) {
            pushScope();
            exec(fc.init);
            while (eval(fc.condition).isTruthy()) {
                for (ASTNode s : fc.body) {
                    exec(s);
                    if (hasReturn) { popScope(); return; }
                    if (hasBreak)  { hasBreak = false; popScope(); return; }
                    if (hasContinue) { hasContinue = false; break; }
                }
                exec(fc.update);
            }
            popScope();
            return;
        }

        // ── range for ─────────────────────────────────────────────────────────
        if (node instanceof ForRangeNode fr) {
            double start = eval(fr.start).asNumber();
            double end   = eval(fr.end).asNumber();
            double step  = fr.step != null ? eval(fr.step).asNumber() : 1.0;
            if (step == 0) throw new RuntimeException("[Runtime Error] for-loop step cannot be 0");
            pushScope();
            for (double i = start; (step>0 ? i<=end : i>=end); i+=step) {
                variables.put(fr.varName, Value.ofNumber(i));
                for (ASTNode s : fr.body) {
                    exec(s);
                    if (hasReturn) { popScope(); return; }
                    if (hasBreak)  { hasBreak=false; popScope(); return; }
                    if (hasContinue) { hasContinue=false; break; }
                }
            }
            popScope();
            return;
        }

        // ── foreach ───────────────────────────────────────────────────────────
        if (node instanceof ForeachNode fn) {
            Value iterable = eval(fn.iterable);
            List<Value> items;
            if (iterable.isArray()) {
                items = new ArrayList<>(iterable.asArray());
            } else if (iterable.isMap()) {
                items = new ArrayList<>();
                for (String k : iterable.asMap().keySet()) items.add(Value.ofString(k));
            } else {
                throw new RuntimeException("[Runtime Error] foreach requires array or map, got " + iterable.kind());
            }
            pushScope();
            for (Value item : items) {
                variables.put(fn.varName, item);
                for (ASTNode s : fn.body) {
                    exec(s);
                    if (hasReturn) { popScope(); return; }
                    if (hasBreak)  { hasBreak=false; popScope(); return; }
                    if (hasContinue) { hasContinue=false; break; }
                }
            }
            popScope();
            return;
        }

        // ── switch ────────────────────────────────────────────────────────────
        if (node instanceof SwitchNode sw) {
            Value target = eval(sw.expr);
            boolean matched = false;
            for (SwitchNode.SwitchCase sc : sw.cases) {
                Value caseVal = eval(sc.value);
                if (valuesEqual(target, caseVal)) {
                    matched = true;
                    for (ASTNode s : sc.body) { exec(s); if (signal()) break; }
                    break; // no fall-through
                }
            }
            if (!matched && !sw.defaultBranch.isEmpty()) {
                for (ASTNode s : sw.defaultBranch) { exec(s); if (signal()) break; }
            }
            // clear break signal if it was used to exit switch
            if (hasBreak) hasBreak = false;
            return;
        }

        // ── return / break / continue ─────────────────────────────────────────
        if (node instanceof ReturnNode   rn) { returnValue = eval(rn.value); hasReturn   = true; return; }
        if (node instanceof BreakNode)        { hasBreak    = true; return; }
        if (node instanceof ContinueNode)     { hasContinue = true; return; }
    }

    private boolean signal() { return hasReturn || hasBreak || hasContinue; }
// Expression evaluator
Value eval(ExprNode expr) {

        if (expr instanceof NumberNode   n) return Value.ofNumber(Double.parseDouble(n.value));
        if (expr instanceof StringNode   s) return Value.ofString(s.value);
        if (expr instanceof NullNode)       return Value.NULL;
        if (expr instanceof ThisNode)       return getVar("__this__");

        if (expr instanceof IdentifierNode id) return getVar(id.name);

        if (expr instanceof ArrayNode an) {
            List<Value> elems = new ArrayList<>();
            for (ExprNode e : an.elements) elems.add(eval(e));
            return Value.ofArray(elems);
        }

        if (expr instanceof MapLiteralNode ml) {
            Map<String,Value> map = new LinkedHashMap<>();
            for (MapLiteralNode.Entry e : ml.entries) {
                map.put(eval(e.key).display(), eval(e.value));
            }
            return Value.ofMap(map);
        }

        if (expr instanceof IndexNode idx) {
            Value container = eval(idx.array);
            Value key       = eval(idx.index);
            if (container.isArray()) return container.asArray().get(toIdx(key, container.asArray().size()));
            if (container.isMap())   {
                Value v = container.asMap().get(key.display());
                return v != null ? v : Value.NULL;
            }
            throw new RuntimeException("[Runtime Error] Cannot index into " + container.kind());
        }

        if (expr instanceof MemberAccessNode ma) return evalMemberAccess(ma);
        if (expr instanceof MethodCallNode   mc) return evalMethodCall(mc);
        if (expr instanceof CallNode         call) return evalCall(call);
        if (expr instanceof UnaryExprNode    un)  return evalUnary(un);
        if (expr instanceof BinaryExprNode   bin) return evalBinary(bin);

        throw new RuntimeException("[Runtime Error] Unknown expression: " + expr.getClass().getSimpleName());
    }

    // ── Member access: obj.field  or  EnumType.VALUE ─────────────────────────

    private Value evalMemberAccess(MemberAccessNode ma) {
        // Check enum access first: Direction.NORTH
        if (ma.object instanceof IdentifierNode id && enums.containsKey(id.name)) {
            return getEnumValue(id.name, ma.field);
        }
        Value obj = eval(ma.object);
        if (obj.isStruct()) {
            Value v = obj.asStruct().fields.get(ma.field);
            if (v == null)
                throw new RuntimeException("[Runtime Error] Struct '" + obj.asStruct().typeName +
                                           "' has no field '" + ma.field + "'");
            return v;
        }
        throw new RuntimeException("[Runtime Error] Cannot access field '" + ma.field +
                                   "' on non-struct value (" + obj.kind() + ")");
    }

    // ── Method call: obj.method(args) ────────────────────────────────────────

    private Value evalMethodCall(MethodCallNode mc) {
        Value obj = eval(mc.object);

        // ── String methods ────────────────────────────────────────────────────
        if (obj.isString()) {
            String s = obj.asString();
            return switch (mc.method) {
                case "length"     -> Value.ofNumber(s.length());
                case "toUpper"    -> Value.ofString(s.toUpperCase());
                case "toLower"    -> Value.ofString(s.toLowerCase());
                case "trim"       -> Value.ofString(s.trim());
                case "contains"   -> Value.ofNumber(s.contains(sArg(mc,0)) ? 1 : 0);
                case "replace"    -> Value.ofString(s.replace(sArg(mc,0), sArg(mc,1)));
                case "startsWith" -> Value.ofNumber(s.startsWith(sArg(mc,0)) ? 1 : 0);
                case "endsWith"   -> Value.ofNumber(s.endsWith(sArg(mc,0)) ? 1 : 0);
                case "indexOf"    -> Value.ofNumber(s.indexOf(sArg(mc,0)));
                case "charAt"     -> Value.ofString(String.valueOf(s.charAt((int)nArg(mc,0))));
                case "substring"  -> {
                    int start = (int) nArg(mc, 0);
                    int end   = mc.args.size() > 1 ? (int) nArg(mc,1) : s.length();
                    yield Value.ofString(s.substring(start, end));
                }
                case "split"      -> {
                    String[] parts = s.split(java.util.regex.Pattern.quote(sArg(mc,0)), -1);
                    List<Value> res = new ArrayList<>();
                    for (String p : parts) res.add(Value.ofString(p));
                    yield Value.ofArray(res);
                }
                default -> throw new RuntimeException("[Runtime Error] String has no method '" + mc.method + "'");
            };
        }

        // ── Array methods ─────────────────────────────────────────────────────
        if (obj.isArray()) {
            List<Value> arr = obj.asArray();
            return switch (mc.method) {
                case "length"   -> Value.ofNumber(arr.size());
                case "append"   -> { arr.add(eval(mc.args.get(0))); yield Value.NULL; }
                case "remove"   -> { arr.remove(toIdx(eval(mc.args.get(0)), arr.size())); yield Value.NULL; }
                case "reverse"  -> { Collections.reverse(arr); yield Value.NULL; }
                case "contains" -> {
                    Value needle = eval(mc.args.get(0));
                    for (Value v : arr) if (valuesEqual(v, needle)) yield Value.ofNumber(1);
                    yield Value.ofNumber(0);
                }
                case "indexOf"  -> {
                    Value needle = eval(mc.args.get(0));
                    for (int i = 0; i < arr.size(); i++) if (valuesEqual(arr.get(i), needle)) yield Value.ofNumber(i);
                    yield Value.ofNumber(-1);
                }
                case "join"     -> {
                    String sep = sArg(mc, 0);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < arr.size(); i++) { if(i>0) sb.append(sep); sb.append(arr.get(i).display()); }
                    yield Value.ofString(sb.toString());
                }
                default -> throw new RuntimeException("[Runtime Error] Array has no method '" + mc.method + "'");
            };
        }

        // ── Map methods ───────────────────────────────────────────────────────
        if (obj.isMap()) {
            Map<String,Value> map = obj.asMap();
            return switch (mc.method) {
                case "containsKey" -> Value.ofNumber(map.containsKey(sArg(mc,0)) ? 1 : 0);
                case "remove"      -> { Value prev = map.remove(sArg(mc,0)); yield prev!=null?prev:Value.NULL; }
                case "size"        -> Value.ofNumber(map.size());
                case "keys"        -> {
                    List<Value> keys = new ArrayList<>();
                    for (String k : map.keySet()) keys.add(Value.ofString(k));
                    yield Value.ofArray(keys);
                }
                case "values"      -> Value.ofArray(new ArrayList<>(map.values()));
                case "has"         -> Value.ofNumber(map.containsKey(sArg(mc,0)) ? 1 : 0);
                default -> throw new RuntimeException("[Runtime Error] Map has no method '" + mc.method + "'");
            };
        }

        // ── Struct user-defined method ────────────────────────────────────────
        if (obj.isStruct()) {
            StructDef sd = structs.get(obj.asStruct().typeName);
            if (sd == null)
                throw new RuntimeException("[Runtime Error] Unknown struct type '" + obj.asStruct().typeName + "'");
            TypedFunctionNode method = sd.methods.get(mc.method);
            if (method == null)
                throw new RuntimeException("[Runtime Error] Struct '" + sd.name +
                                           "' has no method '" + mc.method + "'");
            return callMethod(obj, method, mc.args);
        }

        throw new RuntimeException("[Runtime Error] Value of type " + obj.kind() + " has no method '" + mc.method + "'");
    }

    // ── Struct method invocation ──────────────────────────────────────────────

    private Value callMethod(Value thisVal, TypedFunctionNode method, List<ExprNode> argExprs) {
        if (argExprs.size() != method.params.size())
            throw new RuntimeException("[Runtime Error] Method '" + method.name + "' expects " +
                method.params.size() + " arguments, got " + argExprs.size());
        pushScope();
        variables.put("__this__", thisVal);
        for (int i = 0; i < method.params.size(); i++)
            variables.put(method.params.get(i).name, eval(argExprs.get(i)));
        hasReturn = false;
        for (ASTNode s : method.body) { exec(s); if (hasReturn) break; }
        Value ret = hasReturn ? returnValue : Value.NULL;
        hasReturn = false; returnValue = null;
        popScope();
        return ret;
    }

    // ── Struct instantiation & registration ──────────────────────────────────

    private void registerStruct(StructDefNode sd) {
        List<TypedFunctionNode> methods = new ArrayList<>(sd.methods);
        structs.put(sd.name, new StructDef(sd.name, sd.fields, sd.constructor, methods));
    }

    private Value createStruct(String typeName, List<ExprNode> argExprs) {
        StructDef sd = structs.get(typeName);
        if (sd == null) throw new RuntimeException("[Runtime Error] Unknown struct: '" + typeName + "'");

        Value.StructInstance inst = new Value.StructInstance(typeName);
        // Initialize fields to their defaults (or NULL)
        for (FieldDeclNode f : sd.fields)
            inst.fields.put(f.name, f.defaultValue != null ? eval(f.defaultValue) : Value.NULL);

        Value instVal = Value.ofStruct(inst);

        // Call constructor if present
        if (sd.constructor != null) {
            if (argExprs.size() != sd.constructor.params.size())
                throw new RuntimeException("[Runtime Error] Constructor '" + typeName + "' expects " +
                    sd.constructor.params.size() + " arguments, got " + argExprs.size());
            pushScope();
            variables.put("__this__", instVal);
            for (int i = 0; i < sd.constructor.params.size(); i++)
                variables.put(sd.constructor.params.get(i).name, eval(argExprs.get(i)));
            hasReturn = false;
            for (ASTNode s : sd.constructor.body) { exec(s); if (hasReturn) break; }
            hasReturn = false; returnValue = null;
            popScope();
        }
        return instVal;
    }

    // ── Enum registration & lookup ────────────────────────────────────────────

    private void registerEnum(EnumDefNode ed) {
        enums.put(ed.name, new EnumDef(ed.name, ed.values));
    }

    private Value getEnumValue(String enumName, String valueName) {
        EnumDef ed = enums.get(enumName);
        if (ed == null) throw new RuntimeException("[Runtime Error] Unknown enum: '" + enumName + "'");
        Integer ord = ed.ordinals.get(valueName);
        if (ord == null)
            throw new RuntimeException("[Runtime Error] Enum '" + enumName + "' has no value '" + valueName + "'");
        return Value.ofEnum(new Value.EnumVal(enumName, valueName, ord));
    }

    // ── Import ────────────────────────────────────────────────────────────────

    private void execImport(ImportNode imp) {
        String path = imp.path;
        // Resolve relative to baseDir
        if (!Paths.get(path).isAbsolute()) path = baseDir + File.separator + path;
        if (imported.contains(path)) return; // already imported
        imported.add(path);
        String source;
        try { source = Files.readString(Paths.get(path)); }
        catch (IOException e) {
            throw new RuntimeException("[Import Error] Cannot read '" + path + "': " + e.getMessage());
        }
        List<Token>   tokens = new Lexer(source).tokenize();
        List<ASTNode> ast    = new Parser(tokens).parse();
        // First pass: register definitions from the imported file into THIS interpreter
        for (ASTNode node : ast) registerDefinition(node);
        // Second pass: execute non-definition statements (e.g., const declarations)
        for (ASTNode node : ast) {
            if (!isDefinition(node)) { exec(node); if (hasReturn) break; }
        }
    }
// Built-in function calls
private Value evalCall(CallNode call) {
        String name = call.func;

        // Check if it's a struct constructor
        if (structs.containsKey(name)) return createStruct(name, call.args);

        // Check user-defined functions
        TypedFunctionNode fn = functions.get(name);
        if (fn != null) {
            if (call.args.size() != fn.params.size())
                throw new RuntimeException("[Runtime Error] Function '" + name + "' expects " +
                    fn.params.size() + " args, got " + call.args.size());
            pushScope();
            for (int i = 0; i < fn.params.size(); i++)
                variables.put(fn.params.get(i).name, eval(call.args.get(i)));
            hasReturn = false;
            for (ASTNode s : fn.body) { exec(s); if (hasReturn) break; }
            Value ret = hasReturn ? returnValue : Value.NULL;
            hasReturn = false; returnValue = null;
            popScope();
            return ret;
        }

        // ── Built-in functions ────────────────────────────────────────────────
        return switch (name) {

            // ── I/O ──────────────────────────────────────────────────────────
            case "println" -> {
                StringBuilder sb = new StringBuilder();
                for (ExprNode a : call.args) sb.append(eval(a).display());
                System.out.println(sb); yield Value.NULL;
            }
            case "print" -> {
                StringBuilder sb = new StringBuilder();
                for (ExprNode a : call.args) sb.append(eval(a).display());
                System.out.print(sb); yield Value.NULL;
            }
            case "input" -> {
                if (!call.args.isEmpty()) System.out.print(eval(call.args.get(0)).display());
                yield Value.ofString(stdinScanner.hasNextLine() ? stdinScanner.nextLine() : "");
            }

            // ── Type conversion ───────────────────────────────────────────────
            case "toInt" -> {
                Value v = eval(call.args.get(0));
                if (v.isNumber()) yield Value.ofNumber(Math.floor(v.asNumber()));
                try { yield Value.ofNumber(Long.parseLong(v.asString().trim())); }
                catch (NumberFormatException e) {
                    throw new RuntimeException("[Runtime Error] Cannot convert '" + v.display() + "' to int");
                }
            }
            case "toFloat"  -> {
                Value v = eval(call.args.get(0));
                if (v.isNumber()) yield v;
                try { yield Value.ofNumber(Double.parseDouble(v.asString().trim())); }
                catch (NumberFormatException e) {
                    throw new RuntimeException("[Runtime Error] Cannot convert '" + v.display() + "' to float");
                }
            }
            case "toString" -> Value.ofString(eval(call.args.get(0)).display());
            case "typeOf"   -> {
                Value v = eval(call.args.get(0));
                String typeName = switch (v.kind()) {
                    case NUMBER      -> "int";
                    case STRING      -> "string";
                    case ARRAY       -> "array";
                    case MAP         -> "map";
                    case STRUCT      -> v.asStruct().typeName;
                    case ENUM_VAL    -> v.asEnum().enumName;
                    case FILE_HANDLE -> "file";
                    case NULL        -> "null";
                };
                yield Value.ofString(typeName);
            }

            // ── Math ──────────────────────────────────────────────────────────
            case "sqrt"    -> Value.ofNumber(Math.sqrt(n(call, 0)));
            case "pow"     -> Value.ofNumber(Math.pow(n(call,0), n(call,1)));
            case "abs"     -> Value.ofNumber(Math.abs(n(call, 0)));
            case "floor"   -> Value.ofNumber(Math.floor(n(call, 0)));
            case "ceil"    -> Value.ofNumber(Math.ceil(n(call, 0)));
            case "round"   -> Value.ofNumber((double)Math.round(n(call, 0)));
            case "max"     -> Value.ofNumber(Math.max(n(call,0), n(call,1)));
            case "min"     -> Value.ofNumber(Math.min(n(call,0), n(call,1)));
            case "random"  -> Value.ofNumber(Math.random());
            case "log"     -> Value.ofNumber(Math.log(n(call, 0)));
            case "log10"   -> Value.ofNumber(Math.log10(n(call, 0)));
            case "sin"     -> Value.ofNumber(Math.sin(n(call, 0)));
            case "cos"     -> Value.ofNumber(Math.cos(n(call, 0)));
            case "tan"     -> Value.ofNumber(Math.tan(n(call, 0)));

            // ── Array / collection ────────────────────────────────────────────
            case "len" -> {
                Value v = eval(call.args.get(0));
                if (v.isArray())  yield Value.ofNumber(v.asArray().size());
                if (v.isString()) yield Value.ofNumber(v.asString().length());
                if (v.isMap())    yield Value.ofNumber(v.asMap().size());
                throw new RuntimeException("[Runtime Error] len() requires array, string, or map");
            }
            case "append"  -> { eval(call.args.get(0)).asArray().add(eval(call.args.get(1))); yield Value.NULL; }
            case "remove"  -> { List<Value> a = eval(call.args.get(0)).asArray(); a.remove(toIdx(eval(call.args.get(1)),a.size())); yield Value.NULL; }
            case "reverse" -> { Collections.reverse(eval(call.args.get(0)).asArray()); yield Value.NULL; }
            case "sort"    -> {
                List<Value> arr = eval(call.args.get(0)).asArray();
                arr.sort((a, b) -> {
                    if (a.isNumber() && b.isNumber()) return Double.compare(a.asNumber(), b.asNumber());
                    return a.display().compareTo(b.display());
                });
                yield Value.NULL;
            }

            // ── String ────────────────────────────────────────────────────────
            case "length"     -> Value.ofNumber(s(call,0).length());
            case "toUpper"    -> Value.ofString(s(call,0).toUpperCase());
            case "toLower"    -> Value.ofString(s(call,0).toLowerCase());
            case "trim"       -> Value.ofString(s(call,0).trim());
            case "charAt"     -> Value.ofString(String.valueOf(s(call,0).charAt((int)n(call,1))));
            case "indexOf"    -> Value.ofNumber(s(call,0).indexOf(s(call,1)));
            case "contains"   -> Value.ofNumber(s(call,0).contains(s(call,1)) ? 1 : 0);
            case "replace"    -> Value.ofString(s(call,0).replace(s(call,1), s(call,2)));
            case "startsWith" -> Value.ofNumber(s(call,0).startsWith(s(call,1)) ? 1 : 0);
            case "endsWith"   -> Value.ofNumber(s(call,0).endsWith(s(call,1)) ? 1 : 0);
            case "substring"  -> {
                String str = s(call,0); int start=(int)n(call,1);
                int end = call.args.size()>2?(int)n(call,2):str.length();
                yield Value.ofString(str.substring(start,end));
            }
            case "split"      -> {
                String[] parts = s(call,0).split(java.util.regex.Pattern.quote(s(call,1)),-1);
                List<Value> res = new ArrayList<>();
                for (String p : parts) res.add(Value.ofString(p));
                yield Value.ofArray(res);
            }

            // ── Map ───────────────────────────────────────────────────────────
            case "mapNew"  -> Value.ofMap(new LinkedHashMap<>());
            case "mapGet"  -> {
                Value v = eval(call.args.get(0)).asMap().get(s(call,1));
                yield v != null ? v : Value.NULL;
            }
            case "mapSet"  -> { eval(call.args.get(0)).asMap().put(s(call,1), eval(call.args.get(2))); yield Value.NULL; }
            case "mapKeys" -> {
                List<Value> keys = new ArrayList<>();
                for (String k : eval(call.args.get(0)).asMap().keySet()) keys.add(Value.ofString(k));
                yield Value.ofArray(keys);
            }

            // ── File I/O ──────────────────────────────────────────────────────
            case "open"     -> {
                String path = s(call,0);
                String mode = s(call,1);
                Value.FileHandle fh = new Value.FileHandle(path, mode);
                try {
                    if (mode.equals("r")) {
                        fh.reader = new BufferedReader(new FileReader(path));
                    } else if (mode.equals("w")) {
                        fh.writer = new BufferedWriter(new FileWriter(path, false));
                    } else if (mode.equals("a")) {
                        fh.writer = new BufferedWriter(new FileWriter(path, true));
                    } else {
                        throw new RuntimeException("[Runtime Error] Invalid file mode '" + mode + "'. Use 'r', 'w', or 'a'");
                    }
                } catch (IOException e) {
                    throw new RuntimeException("[File Error] Cannot open '" + path + "': " + e.getMessage());
                }
                yield Value.ofFile(fh);
            }
            case "readLine" -> {
                Value.FileHandle fh = eval(call.args.get(0)).asFile();
                if (fh.closed) throw new RuntimeException("[File Error] File is closed");
                try {
                    String line = fh.reader.readLine();
                    yield line != null ? Value.ofString(line) : Value.NULL;
                } catch (IOException e) {
                    throw new RuntimeException("[File Error] readLine failed: " + e.getMessage());
                }
            }
            case "readAll"  -> {
                Value.FileHandle fh = eval(call.args.get(0)).asFile();
                if (fh.closed) throw new RuntimeException("[File Error] File is closed");
                try {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = fh.reader.readLine()) != null) { sb.append(line).append('\n'); }
                    yield Value.ofString(sb.toString());
                } catch (IOException e) {
                    throw new RuntimeException("[File Error] readAll failed: " + e.getMessage());
                }
            }
            case "write"    -> {
                Value.FileHandle fh = eval(call.args.get(0)).asFile();
                if (fh.closed) throw new RuntimeException("[File Error] File is closed");
                try { fh.writer.write(s(call,1)); fh.writer.flush(); }
                catch (IOException e) { throw new RuntimeException("[File Error] write failed: " + e.getMessage()); }
                yield Value.NULL;
            }
            case "writeLine" -> {
                Value.FileHandle fh = eval(call.args.get(0)).asFile();
                if (fh.closed) throw new RuntimeException("[File Error] File is closed");
                try { fh.writer.write(s(call,1)); fh.writer.newLine(); fh.writer.flush(); }
                catch (IOException e) { throw new RuntimeException("[File Error] writeLine failed: " + e.getMessage()); }
                yield Value.NULL;
            }
            case "close"    -> {
                Value.FileHandle fh = eval(call.args.get(0)).asFile();
                try {
                    if (fh.reader != null) fh.reader.close();
                    if (fh.writer != null) fh.writer.close();
                    fh.closed = true;
                } catch (IOException e) { /* ignore close errors */ }
                yield Value.NULL;
            }
            case "isEOF"    -> {
                Value.FileHandle fh = eval(call.args.get(0)).asFile();
                try { yield fh.reader != null && !fh.reader.ready() ? Value.ofNumber(1) : Value.ofNumber(0); }
                catch (IOException e) { yield Value.ofNumber(1); }
            }

            default -> throw new RuntimeException("[Runtime Error] Unknown function: '" + name + "'");
        };
    }
// Unary / Binary
private Value evalUnary(UnaryExprNode un) {
        return switch (un.op) {
            case "!" -> Value.ofNumber(eval(un.operand).isTruthy() ? 0 : 1);
            case "-" -> Value.ofNumber(-eval(un.operand).asNumber());
            case "++" -> {
                IdentifierNode id = (IdentifierNode) un.operand;
                double nv = getVar(id.name).asNumber() + 1;
                setVar(id.name, Value.ofNumber(nv)); yield Value.ofNumber(nv);
            }
            case "--" -> {
                IdentifierNode id = (IdentifierNode) un.operand;
                double nv = getVar(id.name).asNumber() - 1;
                setVar(id.name, Value.ofNumber(nv)); yield Value.ofNumber(nv);
            }
            default -> throw new RuntimeException("[Runtime Error] Unknown unary op: " + un.op);
        };
    }

    private Value evalBinary(BinaryExprNode bin) {
        Value l = eval(bin.left);
        Value r = eval(bin.right);
        return switch (bin.op) {
            case "+" -> {
                if (l.isNumber() && r.isNumber()) yield Value.ofNumber(l.asNumber() + r.asNumber());
                yield Value.ofString(l.display() + r.display());
            }
            case "-"  -> Value.ofNumber(l.asNumber() - r.asNumber());
            case "*"  -> Value.ofNumber(l.asNumber() * r.asNumber());
            case "/"  -> {
                if (r.asNumber()==0) throw new RuntimeException("[Runtime Error] Division by zero");
                yield Value.ofNumber(l.asNumber() / r.asNumber());
            }
            case "%"  -> {
                if (r.asNumber()==0) throw new RuntimeException("[Runtime Error] Modulo by zero");
                yield Value.ofNumber(l.asNumber() % r.asNumber());
            }
            case "==" -> Value.ofNumber(valuesEqual(l,r) ? 1 : 0);
            case "!=" -> Value.ofNumber(!valuesEqual(l,r) ? 1 : 0);
            case "<"  -> Value.ofNumber(l.asNumber() <  r.asNumber() ? 1 : 0);
            case ">"  -> Value.ofNumber(l.asNumber() >  r.asNumber() ? 1 : 0);
            case "<=" -> Value.ofNumber(l.asNumber() <= r.asNumber() ? 1 : 0);
            case ">=" -> Value.ofNumber(l.asNumber() >= r.asNumber() ? 1 : 0);
            case "&&" -> Value.ofNumber(l.isTruthy() && r.isTruthy() ? 1 : 0);
            case "||" -> Value.ofNumber(l.isTruthy() || r.isTruthy() ? 1 : 0);
            default   -> throw new RuntimeException("[Runtime Error] Unknown operator: " + bin.op);
        };
    }

    private static boolean valuesEqual(Value a, Value b) {
        if (a.isNull() && b.isNull()) return true;
        if (a.isNumber() && b.isNumber()) return a.asNumber() == b.asNumber();
        if (a.isString() && b.isString()) return a.asString().equals(b.asString());
        if (a.isEnum()   && b.isEnum())   return a.asEnum().enumName.equals(b.asEnum().enumName)
                                              && a.asEnum().valueName.equals(b.asEnum().valueName);
        return false;
    }

    private Value applyCompound(String op, Value cur, Value operand, String vn) {
        return switch (op) {
            case "+=" -> {
                if (cur.isNumber() && operand.isNumber()) yield Value.ofNumber(cur.asNumber()+operand.asNumber());
                yield Value.ofString(cur.display()+operand.display());
            }
            case "-=" -> Value.ofNumber(cur.asNumber()-operand.asNumber());
            case "*=" -> Value.ofNumber(cur.asNumber()*operand.asNumber());
            case "/=" -> {
                if (operand.asNumber()==0) throw new RuntimeException("[Runtime Error] Division by zero in /= on '"+vn+"'");
                yield Value.ofNumber(cur.asNumber()/operand.asNumber());
            }
            default -> throw new RuntimeException("[Runtime Error] Unknown compound op: "+op);
        };
    }
// Helpers
private double n(CallNode call, int i) { return eval(call.args.get(i)).asNumber(); }
    private String s(CallNode call, int i) { return eval(call.args.get(i)).asString(); }
    private double nArg(MethodCallNode mc, int i) { return eval(mc.args.get(i)).asNumber(); }
    private String sArg(MethodCallNode mc, int i) { return eval(mc.args.get(i)).asString(); }

    private static int toIdx(Value v, int size) {
        int i = (int) v.asNumber();
        if (i < 0 || i >= size)
            throw new RuntimeException("[Runtime Error] Index " + i + " out of bounds (size=" + size + ")");
        return i;
    }
}
