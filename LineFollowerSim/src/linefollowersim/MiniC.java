package linefollowersim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A small interpreter for the Arduino-style C used on the Code tab: int / long / bool / float / double
 * (and the Arduino integer types), arrays, functions, #define constants, if / else, while, do-while, for,
 * switch, break, continue, return, all C operators, casts. Integer arithmetic follows C (5 / 2 == 2).
 * The host gives it named constants, read-only arrays and functions. A step limit stops endless loops.
 * No Swing in here.
 */
final class MiniC {

    // ------------------------------------------------------------------ errors
    static final class CompileError extends Exception {

        final int line;

        CompileError(int line, String msg) {
            super("line " + line + ": " + msg);
            this.line = line;
        }
    }

    static final class RunError extends RuntimeException {

        final int line;
        boolean shown;

        RunError(int line, String msg) {
            super("line " + line + ": " + msg);
            this.line = line;
        }
    }

    // ------------------------------------------------------------------ what the host offers
    interface Fn {

        double call(double[] a);
    }

    static final class Builtin {

        final int minArgs, maxArgs;
        final boolean intResult;
        final Fn fn;

        Builtin(int minArgs, int maxArgs, boolean intResult, Fn fn) {
            this.minArgs = minArgs;
            this.maxArgs = maxArgs;
            this.intResult = intResult;
            this.fn = fn;
        }
    }

    interface Constant {

        double get();
    }

    interface ArrayRead {

        double get(int i);
    }

    interface Printer {

        void print(String s);
    }

    interface Plotter {

        void plot(String name, double v);
    }

    final Map<String, Builtin> builtins = new HashMap<>();
    final Map<String, Constant> constants = new HashMap<>();
    final Map<String, boolean[]> constantIsInt = new HashMap<>();
    final Map<String, ArrayRead> arrays = new HashMap<>();
    Printer printer = s -> { };
    Plotter plotter = (n, v) -> { };
    long stepLimit = 400_000;

    void constant(String name, boolean isInt, Constant c) {
        constants.put(name, c);
        constantIsInt.put(name, new boolean[]{isInt});
    }

    // ------------------------------------------------------------------ program state
    static final int T_INT = 0, T_BOOL = 1, T_FLOAT = 2, T_VOID = 3;

    /** A global variable (scalar = array of 1). */
    static final class Global {

        final String name;
        final int type, size;
        final boolean isArray, isConst;
        final double[] v;
        List<Expr> init;                 // initial values (null = zeros)
        int line;

        Global(String name, int type, int size, boolean isArray, boolean isConst) {
            this.name = name;
            this.type = type;
            this.size = size;
            this.isArray = isArray;
            this.isConst = isConst;
            v = new double[Math.max(1, size)];
        }
    }

    static final class Function {

        final String name;
        final int ret;
        final List<int[]> params = new ArrayList<>();   // {slot, type}
        Stmt body;
        int slots;
        int line;
        String proto;                    // "float f ( int a )" for the C++ prototypes of the Arduino export
        final List<int[]> vars = new ArrayList<>();       // {slot, type, size, isArray} of parameters and locals
        final List<String> varNames = new ArrayList<>();

        Function(String name, int ret) {
            this.name = name;
            this.ret = ret;
        }
    }

    /** The debugger (Code tab): looks before every statement; null = no debugging. */
    interface Debug {

        void at(int line);

        /** A run error: the call stack is still there to look at. */
        void error(RunError e);
    }

    /** Thrown by the debugger to leave the program at once (Restart while stopped). */
    static final class Abort extends RuntimeException {

        Abort() {
            super("stopped by the debugger", null, false, false);
        }
    }

    /** One call on the stack. */
    final class Frame {

        final Function f;
        final Ctx c;
        int line;

        Frame(Function f, Ctx c, int line) {
            this.f = f;
            this.c = c;
            this.line = line;
        }

        String name() {
            return f.name + "()";
        }

        /** The function's variables that exist now: {name, value}. */
        List<String[]> locals() {
            List<String[]> o = new ArrayList<>();
            for (int[] v : f.vars) {
                if (v[0] < c.slots.length && c.slots[v[0]] != null) {
                    double[] a = c.slots[v[0]];
                    o.add(new String[]{f.varNames.get(f.vars.indexOf(v)), v[3] == 1 ? arrayText(a, v[1]) : valueText(a[0], v[1])});
                }
            }
            return o;
        }
    }

    Debug debug;
    final List<Frame> stack = new ArrayList<>();

    static String valueText(double v, int type) {
        if (type == T_FLOAT) {
            return fmt(v);
        }
        if (type == T_BOOL) {
            return v != 0 ? "true" : "false";
        }
        return Long.toString((long) v);
    }

    static String arrayText(double[] a, int type) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < a.length && i < 16; i++) {
            sb.append(i > 0 ? ", " : "").append(valueText(a[i], type));
        }
        return sb.append(a.length > 16 ? ", ... (" + a.length + ")}" : "}").toString();
    }

    final List<Global> globals = new ArrayList<>();
    private final Map<String, Global> globalByName = new HashMap<>();
    private final Map<String, Function> functions = new HashMap<>();
    private final List<CallExpr> unresolved = new ArrayList<>();
    long steps, runs;
    private int depth;

    // ------------------------------------------------------------------ public use
    /** Compile a program (throws with the line of the first error). */
    void compile(String src) throws CompileError {
        globals.clear();
        globalByName.clear();
        functions.clear();
        unresolved.clear();
        toks = lex(src);
        pos = 0;
        prescanFunctions();
        while (peek().kind != Tok.EOF) {
            topLevel();
        }
        for (CallExpr c : unresolved) {
            Function f = functions.get(c.name);
            if (f == null || f.body == null) {
                throw new CompileError(c.line, (f == null ? "unknown function " : "function without a body: ") + c.name + "()");
            }
            if (c.args.size() != f.params.size()) {
                throw new CompileError(c.line, c.name + "() takes " + f.params.size() + " argument(s), not " + c.args.size());
            }
            c.fn = f;
        }
    }

    /** Learn every function's return type first, so a call before the definition still knows int from float. */
    private void prescanFunctions() {
        int d = 0;
        for (int i = 0; i + 2 < toks.size(); i++) {
            Tok t = toks.get(i);
            if (t.kind == Tok.OP && (t.s.equals("{") || t.s.equals("("))) {
                d++;
            } else if (t.kind == Tok.OP && (t.s.equals("}") || t.s.equals(")"))) {
                d--;
            } else if (d == 0 && t.kind == Tok.ID && toks.get(i + 1).kind == Tok.ID && toks.get(i + 2).s.equals("(")) {
                String ty = t.s;
                int r = ty.equals("void") ? T_VOID : ty.equals("float") || ty.equals("double") ? T_FLOAT
                        : ty.equals("bool") || ty.equals("boolean") ? T_BOOL : INT_TYPES.contains(ty) ? T_INT : -1;
                String name = toks.get(i + 1).s;
                if (r >= 0 && !functions.containsKey(name)) {
                    functions.put(name, new Function(name, r));
                }
            }
        }
    }

    /** C++ prototypes of every function with a body (in the order they appear). */
    List<String> prototypes() {
        List<String> o = new ArrayList<>();
        List<Function> l = new ArrayList<>(functions.values());
        l.sort((a, b) -> Integer.compare(a.line, b.line));
        for (Function f : l) {
            if (f.body != null && f.proto != null) {
                o.add(f.proto + ";");
            }
        }
        return o;
    }

    /** A breakpoint condition: an expression over globals, sensors and the sliders. */
    Expr compileCondition(String src) throws CompileError {
        List<Tok> save = toks;
        int savePos = pos;
        Function saveFn = current;
        try {
            current = null;
            scopes.clear();
            toks = lexPlain(src, 0);
            toks.add(new Tok(Tok.EOF, "", 0, false, 0));
            pos = 0;
            Expr e = expr();
            if (peek().kind != Tok.EOF) {
                throw new CompileError(0, "unexpected " + peek());
            }
            for (CallExpr ce : unresolved) {
                if (ce.fn == null) {
                    Function f = functions.get(ce.name);
                    if (f == null || f.body == null || f.params.size() != ce.args.size()) {
                        throw new CompileError(0, "unknown function " + ce.name + "()");
                    }
                    ce.fn = f;
                }
            }
            return e;
        } finally {
            toks = save;
            pos = savePos;
            current = saveFn;
        }
    }

    /** Evaluate a condition (true / false); counts no steps against the loop. */
    boolean test(Expr e) {
        long s0 = steps;
        try {
            return e.eval(new Ctx()) != 0;
        } finally {
            steps = s0;
        }
    }

    boolean hasFunction(String name) {
        Function f = functions.get(name);
        return f != null && f.body != null;
    }

    /** Globals back to their first values (initialisers run again). */
    void resetGlobals() {
        steps = 0;
        Ctx c = new Ctx();
        for (Global g : globals) {
            java.util.Arrays.fill(g.v, 0);
            if (g.init != null) {
                for (int i = 0; i < g.init.size() && i < g.v.length; i++) {
                    g.v[i] = convert(g.type, g.init.get(i).eval(c));
                }
            }
        }
    }

    /** Call a function without arguments (setup, loop). Returns the steps it took. */
    long run(String name) {
        Function f = functions.get(name);
        if (f == null || f.body == null) {
            return 0;
        }
        steps = 0;
        depth = 0;
        runs++;
        stack.clear();
        Ctx c = new Ctx();
        invoke(f, new double[0], c, f.line);
        return steps;
    }

    // ------------------------------------------------------------------ lexer
    static final class Tok {

        static final int EOF = 0, ID = 1, NUM = 2, STR = 3, OP = 4;
        final int kind, line;
        final String s;
        final double num;
        final boolean isInt;

        Tok(int kind, String s, double num, boolean isInt, int line) {
            this.kind = kind;
            this.s = s;
            this.num = num;
            this.isInt = isInt;
            this.line = line;
        }

        @Override
        public String toString() {
            return kind == EOF ? "the end" : kind == STR ? "\"" + s + "\"" : "'" + s + "'";
        }
    }

    private List<Tok> toks;
    private int pos;
    private final Map<String, List<Tok>> macros = new HashMap<>();

    private static final String[] OPS3 = {"<<=", ">>="};
    private static final String[] OPS2 = {"==", "!=", "<=", ">=", "&&", "||", "++", "--", "+=", "-=", "*=", "/=", "%=", "&=", "|=",
        "^=", "<<", ">>", "->"};

    private List<Tok> lex(String src) throws CompileError {
        macros.clear();
        List<Tok> out = new ArrayList<>();
        int i = 0, line = 1, n = src.length();
        boolean lineStart = true;
        while (i < n) {
            char ch = src.charAt(i);
            if (ch == '\n') {
                line++;
                i++;
                lineStart = true;
                continue;
            }
            if (Character.isWhitespace(ch)) {
                i++;
                continue;
            }
            if (ch == '#' && lineStart) {                              // preprocessor line
                int e = src.indexOf('\n', i);
                if (e < 0) {
                    e = n;
                }
                String l = src.substring(i + 1, e).trim();
                while (l.endsWith("\\") && e < n) {                    // continued line
                    int e2 = src.indexOf('\n', e + 1);
                    if (e2 < 0) {
                        e2 = n;
                    }
                    l = l.substring(0, l.length() - 1) + " " + src.substring(e + 1, e2).trim();
                    e = e2;
                    line++;
                }
                if (l.startsWith("define")) {
                    String rest = l.substring(6).trim();
                    int k = 0;
                    while (k < rest.length() && (Character.isLetterOrDigit(rest.charAt(k)) || rest.charAt(k) == '_')) {
                        k++;
                    }
                    String name = rest.substring(0, k);
                    if (name.isEmpty()) {
                        throw new CompileError(line, "#define needs a name");
                    }
                    if (k < rest.length() && rest.charAt(k) == '(') {
                        throw new CompileError(line, "#define with arguments is not supported: use a function");
                    }
                    String body = rest.substring(k).trim();
                    int c = body.indexOf("//");
                    if (c >= 0) {
                        body = body.substring(0, c);
                    }
                    macros.put(name, lexPlain(body, line));
                }
                i = e;                                                 // #include, #if ... are ignored
                continue;
            }
            lineStart = false;
            if (ch == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (ch == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int e = src.indexOf("*/", i + 2);
                if (e < 0) {
                    throw new CompileError(line, "comment /* is not closed");
                }
                for (int k = i; k < e; k++) {
                    if (src.charAt(k) == '\n') {
                        line++;
                    }
                }
                i = e + 2;
                continue;
            }
            int[] adv = {0};
            Tok t = token(src, i, line, adv);
            if (t.kind == Tok.ID && macros.containsKey(t.s)) {
                for (Tok m : macros.get(t.s)) {
                    out.add(new Tok(m.kind, m.s, m.num, m.isInt, line));
                }
            } else {
                out.add(t);
            }
            i += adv[0];
        }
        out.add(new Tok(Tok.EOF, "", 0, false, line));
        return out;
    }

    private List<Tok> lexPlain(String s, int line) throws CompileError {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            if (Character.isWhitespace(s.charAt(i))) {
                i++;
                continue;
            }
            int[] adv = {0};
            Tok t = token(s, i, line, adv);
            if (t.kind == Tok.ID && macros.containsKey(t.s)) {
                out.addAll(macros.get(t.s));
            } else {
                out.add(t);
            }
            i += adv[0];
        }
        return out;
    }

    private static Tok token(String src, int i, int line, int[] adv) throws CompileError {
        int n = src.length();
        char ch = src.charAt(i);
        if (Character.isLetter(ch) || ch == '_') {
            int k = i;
            while (k < n && (Character.isLetterOrDigit(src.charAt(k)) || src.charAt(k) == '_')) {
                k++;
            }
            adv[0] = k - i;
            return new Tok(Tok.ID, src.substring(i, k), 0, false, line);
        }
        if (Character.isDigit(ch) || ch == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1))) {
            int k = i;
            boolean isInt = true;
            double v;
            if (ch == '0' && k + 1 < n && (src.charAt(k + 1) == 'x' || src.charAt(k + 1) == 'X')) {
                k += 2;
                int st = k;
                while (k < n && Character.digit(src.charAt(k), 16) >= 0) {
                    k++;
                }
                v = Long.parseLong(src.substring(st, k), 16);
            } else if (ch == '0' && k + 1 < n && (src.charAt(k + 1) == 'b' || src.charAt(k + 1) == 'B')) {
                k += 2;
                int st = k;
                while (k < n && (src.charAt(k) == '0' || src.charAt(k) == '1')) {
                    k++;
                }
                v = Long.parseLong(src.substring(st, k), 2);
            } else {
                while (k < n && (Character.isDigit(src.charAt(k)) || src.charAt(k) == '.')) {
                    if (src.charAt(k) == '.') {
                        isInt = false;
                    }
                    k++;
                }
                if (k < n && (src.charAt(k) == 'e' || src.charAt(k) == 'E')) {
                    isInt = false;
                    k++;
                    if (k < n && (src.charAt(k) == '+' || src.charAt(k) == '-')) {
                        k++;
                    }
                    while (k < n && Character.isDigit(src.charAt(k))) {
                        k++;
                    }
                }
                try {
                    v = Double.parseDouble(src.substring(i, k));
                } catch (NumberFormatException ex) {
                    throw new CompileError(line, "bad number " + src.substring(i, k));
                }
            }
            while (k < n && "fFuUlL".indexOf(src.charAt(k)) >= 0) {   // 2.0f, 10UL
                if (src.charAt(k) == 'f' || src.charAt(k) == 'F') {
                    isInt = false;
                }
                k++;
            }
            adv[0] = k - i;
            return new Tok(Tok.NUM, src.substring(i, k), v, isInt, line);
        }
        if (ch == '"') {
            StringBuilder sb = new StringBuilder();
            int k = i + 1;
            while (k < n && src.charAt(k) != '"') {
                if (src.charAt(k) == '\n') {
                    throw new CompileError(line, "a text in \"...\" must end on the same line");
                }
                if (src.charAt(k) == '\\' && k + 1 < n) {
                    char e = src.charAt(++k);
                    sb.append(e == 'n' ? '\n' : e == 't' ? '\t' : e);
                } else {
                    sb.append(src.charAt(k));
                }
                k++;
            }
            if (k >= n) {
                throw new CompileError(line, "text \"... is not closed");
            }
            adv[0] = k + 1 - i;
            return new Tok(Tok.STR, sb.toString(), 0, false, line);
        }
        if (ch == '\'') {
            int k = i + 1;
            if (k < n && src.charAt(k) == '\\') {
                k++;
            }
            if (k + 1 >= n || src.charAt(k + 1) != '\'') {
                throw new CompileError(line, "bad character constant");
            }
            adv[0] = k + 2 - i;
            return new Tok(Tok.NUM, src.substring(i, k + 2), src.charAt(k), true, line);
        }
        for (String o : OPS3) {
            if (src.startsWith(o, i)) {
                adv[0] = 3;
                return new Tok(Tok.OP, o, 0, false, line);
            }
        }
        for (String o : OPS2) {
            if (src.startsWith(o, i)) {
                adv[0] = 2;
                return new Tok(Tok.OP, o, 0, false, line);
            }
        }
        if ("+-*/%<>=!&|^~?:;,(){}[].".indexOf(ch) >= 0) {
            adv[0] = 1;
            return new Tok(Tok.OP, String.valueOf(ch), 0, false, line);
        }
        throw new CompileError(line, "unexpected character '" + ch + "'");
    }

    // ------------------------------------------------------------------ parser helpers
    private Tok peek() {
        return toks.get(pos);
    }

    private Tok peek(int k) {
        return toks.get(Math.min(pos + k, toks.size() - 1));
    }

    private Tok next() {
        return toks.get(pos++ < toks.size() - 1 ? pos - 1 : toks.size() - 1);
    }

    private boolean is(String op) {
        Tok t = peek();
        return (t.kind == Tok.OP || t.kind == Tok.ID) && t.s.equals(op);
    }

    private boolean accept(String op) {
        if (is(op)) {
            pos++;
            return true;
        }
        return false;
    }

    private void expect(String op) throws CompileError {
        if (!accept(op)) {
            Tok prev = pos > 0 ? toks.get(pos - 1) : peek();
            if ((op.equals(";") || op.equals(")")) && prev.line < peek().line) {   // missing at the end of the previous line
                throw new CompileError(prev.line, "missing '" + op + "' at the end of the line");
            }
            throw new CompileError(peek().line, "expected '" + op + "' but found " + peek());
        }
    }

    private String ident() throws CompileError {
        Tok t = next();
        if (t.kind != Tok.ID) {
            throw new CompileError(t.line, "expected a name but found " + t);
        }
        return t.s;
    }

    private static final java.util.Set<String> INT_TYPES = new java.util.HashSet<>(java.util.Arrays.asList("int", "long", "short",
            "char", "byte", "word", "unsigned", "signed", "uint8_t", "uint16_t", "uint32_t", "uint64_t", "int8_t", "int16_t", "int32_t",
            "int64_t", "size_t"));
    private static final java.util.Set<String> QUAL = new java.util.HashSet<>(java.util.Arrays.asList("const", "static", "volatile",
            "unsigned", "signed"));

    private boolean atType() {
        Tok t = peek();
        if (t.kind != Tok.ID) {
            return false;
        }
        return INT_TYPES.contains(t.s) || QUAL.contains(t.s) || t.s.equals("float") || t.s.equals("double") || t.s.equals("bool")
                || t.s.equals("boolean") || t.s.equals("void");
    }

    /** Read a type; returns {type, const 0/1, static 0/1}. */
    private int[] type() throws CompileError {
        int cst = 0, stat = 0, ty = -1;
        boolean any = false;
        while (peek().kind == Tok.ID) {
            String s = peek().s;
            if (s.equals("const")) {
                cst = 1;
            } else if (s.equals("static")) {
                stat = 1;
            } else if (s.equals("volatile")) {
                any = any;
            } else if (s.equals("unsigned") || s.equals("signed")) {
                ty = T_INT;
            } else if (INT_TYPES.contains(s)) {
                ty = T_INT;
            } else if (s.equals("float") || s.equals("double")) {
                ty = T_FLOAT;
            } else if (s.equals("bool") || s.equals("boolean")) {
                ty = T_BOOL;
            } else if (s.equals("void")) {
                ty = T_VOID;
            } else {
                break;
            }
            any = true;
            pos++;
        }
        if (!any || ty < 0) {
            throw new CompileError(peek().line, "expected a type (int, float, bool ...) but found " + peek());
        }
        return new int[]{ty, cst, stat};
    }

    // ------------------------------------------------------------------ top level
    private Function current;                                          // the function being parsed (null = globals)
    private final List<Map<String, int[]>> scopes = new ArrayList<>();  // name -> {slot, type, size, isArray}

    private void topLevel() throws CompileError {
        int line = peek().line;
        if (accept(";")) {
            return;
        }
        int startTok = pos;
        int[] ty = type();
        String name = ident();
        if (accept("(")) {                                             // a function
            Function f = functions.get(name);
            if (f == null) {
                f = new Function(name, ty[0]);
                functions.put(name, f);
            }
            f.line = line;
            current = f;
            scopes.clear();
            scopes.add(new HashMap<>());
            f.params.clear();
            f.vars.clear();
            f.varNames.clear();
            int slot = 0;
            if (!accept(")")) {
                if (is("void") && peek(1).s.equals(")")) {
                    pos += 2;
                } else {
                    do {
                        int[] pt = type();
                        String pn = ident();
                        if (accept("[")) {
                            throw new CompileError(peek().line, "arrays as parameters are not supported: use a global array");
                        }
                        scopes.get(0).put(pn, new int[]{slot, pt[0], 1, 0});
                        f.vars.add(new int[]{slot, pt[0], 1, 0});
                        f.varNames.add(pn);
                        f.params.add(new int[]{slot++, pt[0]});
                    } while (accept(","));
                    expect(")");
                }
            }
            StringBuilder pr = new StringBuilder();
            for (int k = startTok; k < pos; k++) {
                pr.append(k > startTok ? " " : "").append(toks.get(k).s);
            }
            if (accept(";")) {                                         // a prototype
                current = null;
                return;
            }
            f.proto = pr.toString();
            if (f.body != null) {
                throw new CompileError(line, "function " + name + "() is defined twice");
            }
            nextSlot = slot;
            f.body = block();
            f.slots = nextSlot;
            current = null;
            return;
        }
        // global variables
        while (true) {
            declareGlobal(name, ty, line);
            if (!accept(",")) {
                break;
            }
            name = ident();
        }
        expect(";");
    }

    private void declareGlobal(String name, int[] ty, int line) throws CompileError {
        if (ty[0] == T_VOID) {
            throw new CompileError(line, "a variable cannot be void");
        }
        if (globalByName.containsKey(name)) {
            throw new CompileError(line, name + " is declared twice");
        }
        if (constants.containsKey(name) || arrays.containsKey(name) || builtins.containsKey(name)) {
            throw new CompileError(line, name + " is a name the simulator already uses (see Help)");
        }
        int size = 1;
        boolean arr = false;
        List<Expr> init = null;
        if (accept("[")) {
            arr = true;
            size = -1;
            if (!is("]")) {
                Expr se = expr();
                size = (int) se.eval(new Ctx());
            }
            expect("]");
        }
        if (accept("=")) {
            init = new ArrayList<>();
            if (accept("{")) {
                if (!is("}")) {
                    do {
                        init.add(ternary());
                    } while (accept(",") && !is("}"));
                }
                expect("}");
            } else {
                init.add(ternary());
            }
        }
        if (size < 0) {
            size = init == null ? 1 : init.size();
        }
        if (size < 1 || size > 10000) {
            throw new CompileError(line, "array size must be 1 - 10000");
        }
        Global g = new Global(name, ty[0], size, arr, ty[1] == 1);
        g.init = init;
        g.line = line;
        globals.add(g);
        globalByName.put(name, g);
    }

    // ------------------------------------------------------------------ statements
    private int nextSlot;

    private Stmt block() throws CompileError {
        int line = peek().line;
        expect("{");
        scopes.add(new HashMap<>());
        List<Stmt> l = new ArrayList<>();
        while (!is("}")) {
            if (peek().kind == Tok.EOF) {
                throw new CompileError(line, "'{' is never closed");
            }
            l.add(statement());
        }
        expect("}");
        scopes.remove(scopes.size() - 1);
        return new BlockStmt(l, line);
    }

    private Stmt statement() throws CompileError {
        Tok t = peek();
        int line = t.line;
        if (is("{")) {
            return block();
        }
        if (accept(";")) {
            return new BlockStmt(new ArrayList<>(), line);
        }
        if (atType()) {
            Stmt s = localDecl();
            expect(";");
            return s;
        }
        if (accept("if")) {
            expect("(");
            Expr c = expr();
            expect(")");
            Stmt a = statement(), b = null;
            if (accept("else")) {
                b = statement();
            }
            return new IfStmt(c, a, b, line);
        }
        if (accept("while")) {
            expect("(");
            Expr c = expr();
            expect(")");
            return new WhileStmt(c, statement(), false, line);
        }
        if (accept("do")) {
            Stmt body = statement();
            expect("while");
            expect("(");
            Expr c = expr();
            expect(")");
            expect(";");
            return new WhileStmt(c, body, true, line);
        }
        if (accept("for")) {
            expect("(");
            scopes.add(new HashMap<>());
            Stmt init = null;
            if (!is(";")) {
                init = atType() ? localDecl() : new ExprStmt(expr(), line);
            }
            expect(";");
            Expr c = is(";") ? null : expr();
            expect(";");
            List<Expr> st = new ArrayList<>();
            if (!is(")")) {
                do {
                    st.add(expr());
                } while (accept(","));
            }
            expect(")");
            Stmt body = statement();
            scopes.remove(scopes.size() - 1);
            return new ForStmt(init, c, st, body, line);
        }
        if (accept("switch")) {
            expect("(");
            Expr v = expr();
            expect(")");
            expect("{");
            scopes.add(new HashMap<>());
            List<Stmt> body = new ArrayList<>();
            List<double[]> cases = new ArrayList<>();                // {value, statement index}
            int def = -1;
            while (!accept("}")) {
                if (accept("case")) {
                    Expr ce = ternary();
                    expect(":");
                    cases.add(new double[]{ce.eval(new Ctx()), body.size()});
                } else if (accept("default")) {
                    expect(":");
                    def = body.size();
                } else if (peek().kind == Tok.EOF) {
                    throw new CompileError(line, "switch '{' is never closed");
                } else {
                    body.add(statement());
                }
            }
            scopes.remove(scopes.size() - 1);
            return new SwitchStmt(v, cases, def, body, line);
        }
        if (accept("break")) {
            expect(";");
            return new Jump(1, line);
        }
        if (accept("continue")) {
            expect(";");
            return new Jump(2, line);
        }
        if (accept("return")) {
            Expr e = is(";") ? null : expr();
            expect(";");
            return new ReturnStmt(e, current == null ? T_VOID : current.ret, line);
        }
        if (t.kind == Tok.ID && (t.s.equals("delay") || t.s.equals("delayMicroseconds")) && peek(1).s.equals("(")) {
            throw new CompileError(line, t.s + "() would stop the whole loop: use a timer with millis() instead (see Help)");
        }
        Expr e = expr();
        expect(";");
        return new ExprStmt(e, line);
    }

    private Stmt localDecl() throws CompileError {
        int line = peek().line;
        int[] ty = type();
        List<Stmt> l = new ArrayList<>();
        do {
            String name = ident();
            if (ty[0] == T_VOID) {
                throw new CompileError(line, "a variable cannot be void");
            }
            if (ty[2] == 1) {                                          // static local: a hidden global
                String hidden = current.name + "$" + name;
                if (!globalByName.containsKey(hidden)) {
                    int save = pos;
                    declareGlobal(hidden, ty, line);
                    pos = save;
                }
                Global g = globalByName.get(hidden);
                skipDeclTail();
                scopes.get(scopes.size() - 1).put(name, new int[]{-1, g.type, g.size, g.isArray ? 1 : 0, globals.indexOf(g)});
                continue;
            }
            int size = 1;
            boolean arr = false;
            List<Expr> init = null;
            if (accept("[")) {
                arr = true;
                size = -1;
                if (!is("]")) {
                    size = (int) expr().eval(new Ctx());
                }
                expect("]");
            }
            if (accept("=")) {
                init = new ArrayList<>();
                if (accept("{")) {
                    if (!is("}")) {
                        do {
                            init.add(ternary());
                        } while (accept(",") && !is("}"));
                    }
                    expect("}");
                } else {
                    init.add(ternary());
                }
            }
            if (size < 0) {
                size = init == null ? 1 : init.size();
            }
            if (size < 1 || size > 10000) {
                throw new CompileError(line, "array size must be 1 - 10000");
            }
            Map<String, int[]> sc = scopes.get(scopes.size() - 1);
            if (sc.containsKey(name)) {
                throw new CompileError(line, name + " is declared twice here");
            }
            int slot = nextSlot++;
            sc.put(name, new int[]{slot, ty[0], size, arr ? 1 : 0});
            current.vars.add(new int[]{slot, ty[0], size, arr ? 1 : 0});
            current.varNames.add(name);
            l.add(new LocalDecl(slot, ty[0], size, init, line));
        } while (accept(","));
        return l.size() == 1 ? l.get(0) : new BlockStmt(l, line);
    }

    /** Skip "[n] = {...}" after a static local that was already declared once. */
    private void skipDeclTail() throws CompileError {
        int depthB = 0;
        while (peek().kind != Tok.EOF) {
            if (depthB == 0 && (is(",") || is(";"))) {
                return;
            }
            if (is("{") || is("[") || is("(")) {
                depthB++;
            } else if (is("}") || is("]") || is(")")) {
                depthB--;
            }
            pos++;
        }
    }

    // ------------------------------------------------------------------ expressions
    private Expr expr() throws CompileError {
        Expr e = assign();
        while (is(",") && false) {
            pos++;
        }
        return e;
    }

    private Expr assign() throws CompileError {
        Expr left = ternary();
        Tok t = peek();
        if (t.kind == Tok.OP && (t.s.equals("=") || t.s.length() >= 2 && t.s.endsWith("=") && !t.s.equals("==") && !t.s.equals("!=")
                && !t.s.equals("<=") && !t.s.equals(">="))) {
            pos++;
            if (!(left instanceof LValue)) {
                throw new CompileError(t.line, "the left of " + t.s + " must be a variable or an array element");
            }
            Expr right = assign();
            return new AssignExpr((LValue) left, t.s, right, t.line);
        }
        return left;
    }

    private Expr ternary() throws CompileError {
        Expr c = binary(0);
        if (is("?")) {
            int line = next().line;
            Expr a = assign();
            expect(":");
            Expr b = assign();
            return new TernaryExpr(c, a, b, line);
        }
        return c;
    }

    private static final String[][] LEVELS = {{"||"}, {"&&"}, {"|"}, {"^"}, {"&"}, {"==", "!="}, {"<", "<=", ">", ">="}, {"<<", ">>"},
        {"+", "-"}, {"*", "/", "%"}};

    private Expr binary(int level) throws CompileError {
        if (level == LEVELS.length) {
            return unary();
        }
        Expr left = binary(level + 1);
        while (true) {
            Tok t = peek();
            String op = null;
            if (t.kind == Tok.OP) {
                for (String o : LEVELS[level]) {
                    if (t.s.equals(o)) {
                        op = o;
                    }
                }
            }
            if (op == null) {
                return left;
            }
            pos++;
            Expr right = binary(level + 1);
            left = new BinExpr(op, left, right, t.line);
        }
    }

    private Expr unary() throws CompileError {
        Tok t = peek();
        if (t.kind == Tok.OP) {
            switch (t.s) {
                case "-":
                case "+":
                case "!":
                case "~":
                    pos++;
                    return new UnExpr(t.s, unary(), t.line);
                case "++":
                case "--": {
                    pos++;
                    Expr e = unary();
                    if (!(e instanceof LValue)) {
                        throw new CompileError(t.line, t.s + " needs a variable");
                    }
                    return new IncExpr((LValue) e, t.s.equals("++") ? 1 : -1, true, t.line);
                }
                case "(":
                    if (peek(1).kind == Tok.ID && isTypeName(peek(1).s) && peek(2).s.equals(")")) {   // a cast
                        pos++;
                        int[] ty = type();
                        expect(")");
                        return new CastExpr(ty[0], unary(), t.line);
                    }
                    break;
                default:
            }
        }
        return postfix(primary());
    }

    private static boolean isTypeName(String s) {
        return INT_TYPES.contains(s) || s.equals("float") || s.equals("double") || s.equals("bool") || s.equals("boolean");
    }

    private Expr postfix(Expr e) throws CompileError {
        while (true) {
            Tok t = peek();
            if (t.kind == Tok.OP && (t.s.equals("++") || t.s.equals("--"))) {
                pos++;
                if (!(e instanceof LValue)) {
                    throw new CompileError(t.line, t.s + " needs a variable");
                }
                e = new IncExpr((LValue) e, t.s.equals("++") ? 1 : -1, false, t.line);
            } else {
                return e;
            }
        }
    }

    private Expr primary() throws CompileError {
        Tok t = next();
        int line = t.line;
        if (t.kind == Tok.NUM) {
            return new NumExpr(t.num, t.isInt, line);
        }
        if (t.kind == Tok.STR) {
            return new StrExpr(t.s, line);
        }
        if (t.kind == Tok.OP && t.s.equals("(")) {
            Expr e = expr();
            expect(")");
            return e;
        }
        if (t.kind != Tok.ID) {
            throw new CompileError(line, "unexpected " + t);
        }
        String name = t.s;
        switch (name) {
            case "true":
            case "HIGH":
                return new NumExpr(1, true, line);
            case "false":
            case "LOW":
                return new NumExpr(0, true, line);
            case "PI":
                return new NumExpr(Math.PI, false, line);
            case "NAN":
                return new NumExpr(Double.NaN, false, line);
            case "INFINITY":
                return new NumExpr(Double.POSITIVE_INFINITY, false, line);
            default:
        }
        if (accept("(")) {                                             // a call
            List<Expr> args = new ArrayList<>();
            if (!is(")")) {
                do {
                    args.add(assign());
                } while (accept(","));
            }
            expect(")");
            if (name.equals("print") || name.equals("println")) {
                return new PrintExpr(args, line);
            }
            if (name.equals("plot")) {
                if (args.size() != 2 || !(args.get(0) instanceof StrExpr)) {
                    throw new CompileError(line, "plot(\"name\", value)");
                }
                return new PlotExpr(((StrExpr) args.get(0)).s, args.get(1), line);
            }
            for (Expr a : args) {
                if (a instanceof StrExpr) {
                    throw new CompileError(line, "text in \"...\" only works in print() and plot()");
                }
            }
            Builtin b = builtins.get(name);
            if (b != null && !functions.containsKey(name)) {
                if (args.size() < b.minArgs || args.size() > b.maxArgs) {
                    throw new CompileError(line, name + "() takes " + (b.minArgs == b.maxArgs ? "" + b.minArgs : b.minArgs + "-" + b.maxArgs)
                            + " argument(s)");
                }
                return new BuiltinCall(name, b, args, line);
            }
            CallExpr c = new CallExpr(name, args, line);
            unresolved.add(c);
            return c;
        }
        if (accept("[")) {                                             // an array element
            Expr idx = expr();
            expect("]");
            ArrayRead ar = arrays.get(name);
            int[] loc = findLocal(name);
            if (loc != null) {
                return new ElemRef(loc[4 < loc.length ? 0 : 0], loc, null, idx, line);
            }
            Global g = globalByName.get(name);
            if (g != null) {
                return new ElemRef(-1, null, g, idx, line);
            }
            if (ar != null) {
                return new BuiltinElem(name, ar, idx, line);
            }
            throw new CompileError(line, "unknown array " + name);
        }
        int[] loc = findLocal(name);
        if (loc != null) {
            return new VarRef(loc, null, line);
        }
        Global g = globalByName.get(name);
        if (g != null) {
            return new VarRef(null, g, line);
        }
        Constant c = constants.get(name);
        if (c != null) {
            return new ConstExpr(name, c, constantIsInt.get(name)[0], line);
        }
        if (arrays.containsKey(name)) {
            throw new CompileError(line, name + " is an array: use " + name + "[i]");
        }
        throw new CompileError(line, "unknown name " + name + " (declare it first, or see Help for what the simulator offers)");
    }

    /** {slot, type, size, isArray[, globalIndex for static locals]} or null. */
    private int[] findLocal(String name) {
        for (int i = scopes.size() - 1; i >= 0 && current != null; i--) {
            int[] v = scopes.get(i).get(name);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ run time
    final class Ctx {

        double[][] slots = new double[0][];
        double ret;
    }

    private void tick(int line) {
        if (++steps > stepLimit) {
            throw new RunError(line, "more than " + stepLimit + " steps in one loop: an endless while / for?");
        }
    }

    static double convert(int type, double v) {
        if (type == T_INT) {
            return Double.isNaN(v) ? 0 : (double) (long) v;
        }
        if (type == T_BOOL) {
            return v != 0 && !Double.isNaN(v) ? 1 : 0;
        }
        return v;
    }

    private double invoke(Function f, double[] args, Ctx caller, int line) {
        if (++depth > 64) {
            depth = 0;
            throw new RunError(line, "functions call each other too deep (endless recursion?)");
        }
        Ctx c = new Ctx();
        c.slots = new double[Math.max(f.slots, f.params.size())][];
        for (int i = 0; i < f.params.size(); i++) {
            int[] p = f.params.get(i);
            c.slots[p[0]] = new double[]{convert(p[1], args[i])};
        }
        c.ret = 0;
        stack.add(new Frame(f, c, f.line));
        try {
            f.body.exec(c);
        } catch (RunError e) {
            if (debug != null && !e.shown) {
                e.shown = true;
                debug.error(e);                                        // look at the variables before the stack goes
            }
            throw e;
        } finally {
            stack.remove(stack.size() - 1);
        }
        depth--;
        return f.ret == T_VOID ? 0 : convert(f.ret, c.ret);
    }

    abstract static class Expr {

        final int line;
        boolean isInt;

        Expr(int line) {
            this.line = line;
        }

        abstract double eval(Ctx c);
    }

    abstract static class LValue extends Expr {

        LValue(int line) {
            super(line);
        }

        abstract double get(Ctx c);

        abstract void set(Ctx c, double v);

        abstract int type();
    }

    static final class NumExpr extends Expr {

        final double v;

        NumExpr(double v, boolean isInt, int line) {
            super(line);
            this.v = v;
            this.isInt = isInt;
        }

        @Override
        double eval(Ctx c) {
            return v;
        }
    }

    static final class StrExpr extends Expr {

        final String s;

        StrExpr(String s, int line) {
            super(line);
            this.s = s;
        }

        @Override
        double eval(Ctx c) {
            return Double.NaN;
        }
    }

    final class ConstExpr extends Expr {

        final Constant k;

        ConstExpr(String name, Constant k, boolean isInt, int line) {
            super(line);
            this.k = k;
            this.isInt = isInt;
        }

        @Override
        double eval(Ctx c) {
            tick(line);
            return k.get();
        }
    }

    final class VarRef extends LValue {

        final int[] loc;
        final Global g;

        VarRef(int[] loc, Global g, int line) {
            super(line);
            Global gg = g;
            if (loc != null && loc.length > 4) {                       // a static local
                gg = globals.get(loc[4]);
                this.loc = null;
            } else {
                this.loc = loc;
            }
            this.g = gg;
            isInt = type() != T_FLOAT;
        }

        @Override
        int type() {
            return loc != null ? loc[1] : g.type;
        }

        @Override
        double eval(Ctx c) {
            return get(c);
        }

        @Override
        double get(Ctx c) {
            tick(line);
            if (loc != null) {
                double[] s = c.slots[loc[0]];
                if (s == null) {
                    throw new RunError(line, "variable used before it is declared");
                }
                return s[0];
            }
            return g.v[0];
        }

        @Override
        void set(Ctx c, double v) {
            if (loc != null) {
                c.slots[loc[0]][0] = convert(loc[1], v);
            } else {
                if (g.isConst) {
                    throw new RunError(line, g.name + " is const");
                }
                g.v[0] = convert(g.type, v);
            }
        }
    }

    final class ElemRef extends LValue {

        final int[] loc;
        final Global g;
        final Expr idx;

        ElemRef(int unused, int[] loc, Global g, Expr idx, int line) {
            super(line);
            Global gg = g;
            if (loc != null && loc.length > 4) {
                gg = globals.get(loc[4]);
                this.loc = null;
            } else {
                this.loc = loc;
            }
            this.g = gg;
            this.idx = idx;
            isInt = type() != T_FLOAT;
        }

        @Override
        int type() {
            return loc != null ? loc[1] : g.type;
        }

        private double[] arr(Ctx c) {
            return loc != null ? c.slots[loc[0]] : g.v;
        }

        private int index(Ctx c, double[] a) {
            double d = idx.eval(c);
            int i = (int) d;
            if (Double.isNaN(d) || i < 0 || i >= a.length) {
                throw new RunError(line, "index " + (Double.isNaN(d) ? "NaN" : String.valueOf(i)) + " is outside the array (0 - "
                        + (a.length - 1) + ")");
            }
            return i;
        }

        @Override
        double eval(Ctx c) {
            return get(c);
        }

        @Override
        double get(Ctx c) {
            tick(line);
            double[] a = arr(c);
            return a[index(c, a)];
        }

        @Override
        void set(Ctx c, double v) {
            if (g != null && g.isConst) {
                throw new RunError(line, g.name + " is const");
            }
            double[] a = arr(c);
            a[index(c, a)] = convert(type(), v);
        }
    }

    final class BuiltinElem extends Expr {

        final String name;
        final ArrayRead ar;
        final Expr idx;

        BuiltinElem(String name, ArrayRead ar, Expr idx, int line) {
            super(line);
            this.name = name;
            this.ar = ar;
            this.idx = idx;
        }

        @Override
        double eval(Ctx c) {
            tick(line);
            double d = idx.eval(c);
            if (Double.isNaN(d)) {
                throw new RunError(line, name + "[NaN]");
            }
            return ar.get((int) d);
        }
    }

    final class AssignExpr extends Expr {

        final LValue l;
        final String op;
        final Expr r;

        AssignExpr(LValue l, String op, Expr r, int line) {
            super(line);
            this.l = l;
            this.op = op;
            this.r = r;
            isInt = l.isInt;
        }

        @Override
        double eval(Ctx c) {
            tick(line);
            double v = r.eval(c);
            if (!op.equals("=")) {
                v = apply(op.substring(0, op.length() - 1), l.get(c), v, l.isInt && r.isInt, line);
            }
            l.set(c, v);
            return l.get(c);
        }
    }

    final class IncExpr extends Expr {

        final LValue l;
        final int d;
        final boolean pre;

        IncExpr(LValue l, int d, boolean pre, int line) {
            super(line);
            this.l = l;
            this.d = d;
            this.pre = pre;
            isInt = l.isInt;
        }

        @Override
        double eval(Ctx c) {
            double old = l.get(c);
            l.set(c, old + d);
            return pre ? l.get(c) : old;
        }
    }

    final class TernaryExpr extends Expr {

        final Expr c0, a, b;

        TernaryExpr(Expr c0, Expr a, Expr b, int line) {
            super(line);
            this.c0 = c0;
            this.a = a;
            this.b = b;
            isInt = a.isInt && b.isInt;
        }

        @Override
        double eval(Ctx c) {
            tick(line);
            return c0.eval(c) != 0 ? a.eval(c) : b.eval(c);
        }
    }

    final class BinExpr extends Expr {

        final String op;
        final Expr a, b;
        final boolean ints;

        BinExpr(String op, Expr a, Expr b, int line) {
            super(line);
            this.op = op;
            this.a = a;
            this.b = b;
            ints = a.isInt && b.isInt;
            switch (op) {
                case "==":
                case "!=":
                case "<":
                case "<=":
                case ">":
                case ">=":
                case "&&":
                case "||":
                case "&":
                case "|":
                case "^":
                case "<<":
                case ">>":
                    isInt = true;
                    break;
                case "%":
                    isInt = true;
                    break;
                default:
                    isInt = ints;
            }
        }

        @Override
        double eval(Ctx c) {
            tick(line);
            if (op.equals("&&")) {
                return a.eval(c) != 0 && b.eval(c) != 0 ? 1 : 0;
            }
            if (op.equals("||")) {
                return a.eval(c) != 0 || b.eval(c) != 0 ? 1 : 0;
            }
            return apply(op, a.eval(c), b.eval(c), ints, line);
        }
    }

    final class UnExpr extends Expr {

        final String op;
        final Expr e;

        UnExpr(String op, Expr e, int line) {
            super(line);
            this.op = op;
            this.e = e;
            isInt = op.equals("!") || op.equals("~") || e.isInt;
        }

        @Override
        double eval(Ctx c) {
            tick(line);
            double v = e.eval(c);
            switch (op) {
                case "-":
                    return -v;
                case "!":
                    return v == 0 ? 1 : 0;
                case "~":
                    return (double) ~(long) v;
                default:
                    return v;
            }
        }
    }

    final class CastExpr extends Expr {

        final int type;
        final Expr e;

        CastExpr(int type, Expr e, int line) {
            super(line);
            this.type = type;
            this.e = e;
            isInt = type != T_FLOAT;
        }

        @Override
        double eval(Ctx c) {
            return convert(type, e.eval(c));
        }
    }

    final class BuiltinCall extends Expr {

        final String name;
        final Builtin b;
        final Expr[] args;

        BuiltinCall(String name, Builtin b, List<Expr> args, int line) {
            super(line);
            this.name = name;
            this.b = b;
            this.args = args.toArray(new Expr[0]);
            boolean allInt = true;
            for (Expr a : this.args) {
                allInt &= a.isInt;
            }
            isInt = b.intResult || (allInt && (name.equals("abs") || name.equals("min") || name.equals("max") || name.equals("constrain")
                    || name.equals("map") || name.equals("sq")));
        }

        @Override
        double eval(Ctx c) {
            tick(line);
            double[] v = new double[args.length];
            for (int i = 0; i < v.length; i++) {
                v[i] = args[i].eval(c);
            }
            try {
                double r = b.fn.call(v);
                return isInt ? convert(T_INT, r) : r;
            } catch (RunError ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw new RunError(line, name + "(): " + ex.getMessage());
            }
        }
    }

    final class CallExpr extends Expr {

        final String name;
        final List<Expr> args;
        Function fn;

        CallExpr(String name, List<Expr> args, int line) {
            super(line);
            this.name = name;
            this.args = args;
            Function f = functions.get(name);
            isInt = f != null && f.ret != T_FLOAT;
        }

        @Override
        double eval(Ctx c) {
            tick(line);
            double[] v = new double[args.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = args.get(i).eval(c);
            }
            return invoke(fn, v, c, line);
        }
    }

    final class PrintExpr extends Expr {

        final List<Expr> args;

        PrintExpr(List<Expr> args, int line) {
            super(line);
            this.args = args;
        }

        @Override
        double eval(Ctx c) {
            tick(line);
            StringBuilder sb = new StringBuilder();
            for (Expr a : args) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                String txt = text(a, c);
                if (txt != null) {
                    sb.append(txt);
                } else {
                    double v = a.eval(c);
                    sb.append(a.isInt && !Double.isNaN(v) ? Long.toString((long) v) : fmt(v));
                }
            }
            printer.print(sb.toString());
            return 0;
        }
    }

    /** The text of a "..." argument (also inside ? :), or null for a number. */
    private static String text(Expr a, Ctx c) {
        if (a instanceof StrExpr) {
            return ((StrExpr) a).s;
        }
        if (a instanceof TernaryExpr) {
            TernaryExpr t = (TernaryExpr) a;
            if (t.a instanceof StrExpr || t.b instanceof StrExpr || t.a instanceof TernaryExpr || t.b instanceof TernaryExpr) {
                Expr pick = t.c0.eval(c) != 0 ? t.a : t.b;
                String s = text(pick, c);
                return s != null ? s : null;
            }
        }
        return null;
    }

    final class PlotExpr extends Expr {

        final String name;
        final Expr v;

        PlotExpr(String name, Expr v, int line) {
            super(line);
            this.name = name;
            this.v = v;
        }

        @Override
        double eval(Ctx c) {
            tick(line);
            plotter.plot(name, v.eval(c));
            return 0;
        }
    }

    static double apply(String op, double x, double y, boolean ints, int line) {
        switch (op) {
            case "+":
                return ints ? (double) ((long) x + (long) y) : x + y;
            case "-":
                return ints ? (double) ((long) x - (long) y) : x - y;
            case "*":
                return ints ? (double) ((long) x * (long) y) : x * y;
            case "/":
                if (ints) {
                    if ((long) y == 0) {
                        throw new RunError(line, "division by zero");
                    }
                    return (double) ((long) x / (long) y);
                }
                return x / y;
            case "%":
                if ((long) y == 0) {
                    throw new RunError(line, "% by zero");
                }
                if (!ints) {
                    throw new RunError(line, "% needs whole numbers (use fmod for floats)");
                }
                return (double) ((long) x % (long) y);
            case "==":
                return x == y ? 1 : 0;
            case "!=":
                return x != y ? 1 : 0;
            case "<":
                return x < y ? 1 : 0;
            case "<=":
                return x <= y ? 1 : 0;
            case ">":
                return x > y ? 1 : 0;
            case ">=":
                return x >= y ? 1 : 0;
            case "&":
                return (double) ((long) x & (long) y);
            case "|":
                return (double) ((long) x | (long) y);
            case "^":
                return (double) ((long) x ^ (long) y);
            case "<<":
                return (double) ((long) x << (long) y);
            case ">>":
                return (double) ((long) x >> (long) y);
            default:
                throw new RunError(line, "unknown operator " + op);
        }
    }

    static String fmt(double v) {
        if (Double.isNaN(v)) {
            return "nan";
        }
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    // ---- statements: exec returns 0 normal, 1 break, 2 continue, 3 return
    abstract class Stmt {

        final int line;

        Stmt(int line) {
            this.line = line;
        }

        abstract int exec(Ctx c);

        /** Run with the debugger's look first (blocks are not stops of their own). */
        final int run(Ctx c) {
            if (debug != null && !(this instanceof BlockStmt)) {
                if (!stack.isEmpty()) {
                    stack.get(stack.size() - 1).line = line;
                }
                debug.at(line);
            }
            return exec(c);
        }
    }

    final class BlockStmt extends Stmt {

        final Stmt[] l;

        BlockStmt(List<Stmt> l, int line) {
            super(line);
            this.l = l.toArray(new Stmt[0]);
        }

        @Override
        int exec(Ctx c) {
            for (Stmt s : l) {
                int r = s.run(c);
                if (r != 0) {
                    return r;
                }
            }
            return 0;
        }
    }

    final class ExprStmt extends Stmt {

        final Expr e;

        ExprStmt(Expr e, int line) {
            super(line);
            this.e = e;
        }

        @Override
        int exec(Ctx c) {
            e.eval(c);
            return 0;
        }
    }

    final class LocalDecl extends Stmt {

        final int slot, type, size;
        final List<Expr> init;

        LocalDecl(int slot, int type, int size, List<Expr> init, int line) {
            super(line);
            this.slot = slot;
            this.type = type;
            this.size = size;
            this.init = init;
        }

        @Override
        int exec(Ctx c) {
            tick(line);
            if (c.slots.length <= slot) {
                c.slots = java.util.Arrays.copyOf(c.slots, slot + 8);
            }
            double[] v = new double[size];
            if (init != null) {
                for (int i = 0; i < init.size() && i < size; i++) {
                    v[i] = convert(type, init.get(i).eval(c));
                }
            }
            c.slots[slot] = v;
            return 0;
        }
    }

    final class IfStmt extends Stmt {

        final Expr c0;
        final Stmt a, b;

        IfStmt(Expr c0, Stmt a, Stmt b, int line) {
            super(line);
            this.c0 = c0;
            this.a = a;
            this.b = b;
        }

        @Override
        int exec(Ctx c) {
            if (c0.eval(c) != 0) {
                return a.run(c);
            }
            return b == null ? 0 : b.run(c);
        }
    }

    final class WhileStmt extends Stmt {

        final Expr c0;
        final Stmt body;
        final boolean doFirst;

        WhileStmt(Expr c0, Stmt body, boolean doFirst, int line) {
            super(line);
            this.c0 = c0;
            this.body = body;
            this.doFirst = doFirst;
        }

        @Override
        int exec(Ctx c) {
            boolean first = doFirst;
            while (first || c0.eval(c) != 0) {
                first = false;
                tick(line);
                int r = body.run(c);
                if (r == 1) {
                    break;
                }
                if (r == 3) {
                    return 3;
                }
            }
            return 0;
        }
    }

    final class ForStmt extends Stmt {

        final Stmt init;
        final Expr c0;
        final List<Expr> step;
        final Stmt body;

        ForStmt(Stmt init, Expr c0, List<Expr> step, Stmt body, int line) {
            super(line);
            this.init = init;
            this.c0 = c0;
            this.step = step;
            this.body = body;
        }

        @Override
        int exec(Ctx c) {
            if (init != null) {
                init.run(c);
            }
            while (c0 == null || c0.eval(c) != 0) {
                tick(line);
                int r = body.run(c);
                if (r == 1) {
                    break;
                }
                if (r == 3) {
                    return 3;
                }
                for (Expr s : step) {
                    s.eval(c);
                }
            }
            return 0;
        }
    }

    final class SwitchStmt extends Stmt {

        final Expr v;
        final List<double[]> cases;
        final int def;
        final Stmt[] body;

        SwitchStmt(Expr v, List<double[]> cases, int def, List<Stmt> body, int line) {
            super(line);
            this.v = v;
            this.cases = cases;
            this.def = def;
            this.body = body.toArray(new Stmt[0]);
        }

        @Override
        int exec(Ctx c) {
            double x = v.eval(c);
            int start = def;
            for (double[] k : cases) {
                if (k[0] == x) {
                    start = (int) k[1];
                    break;
                }
            }
            if (start < 0) {
                return 0;
            }
            for (int i = start; i < body.length; i++) {
                int r = body[i].run(c);
                if (r == 1) {
                    return 0;
                }
                if (r != 0) {
                    return r;
                }
            }
            return 0;
        }
    }

    final class Jump extends Stmt {

        final int kind;

        Jump(int kind, int line) {
            super(line);
            this.kind = kind;
        }

        @Override
        int exec(Ctx c) {
            return kind;
        }
    }

    final class ReturnStmt extends Stmt {

        final Expr e;
        final int type;

        ReturnStmt(Expr e, int type, int line) {
            super(line);
            this.e = e;
            this.type = type;
        }

        @Override
        int exec(Ctx c) {
            if (e != null) {
                c.ret = convert(type == T_VOID ? T_FLOAT : type, e.eval(c));
            }
            return 3;
        }
    }
}
