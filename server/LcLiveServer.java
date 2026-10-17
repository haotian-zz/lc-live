import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.sun.source.tree.*;
import com.sun.source.util.*;

import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * lc-live compile server.
 *
 * Runs a persistent JVM that compiles LeetCode Java solutions in memory and
 * returns javac diagnostics as JSON. Zero dependencies; needs a JDK (11+).
 *
 *   java server/LcLiveServer.java [port]        (env LC_LIVE_HOST overrides the bind address)
 *
 *   GET  /health            -> {"ok":true,...}
 *   POST /compile  (body = raw Java source, text/plain)
 *        -> {"ok":true,"ms":42,"diagnostics":[{"line":3,"col":9,"endLine":3,"endCol":12,
 *                                            "severity":"error","message":"..."}]}
 *   POST /complete (body = raw Java source, header X-Lc-Offset = caret offset in the source)
 *        -> {"ok":true,"ms":30,"incomplete":false,
 *            "items":[{"label":"offer","kind":"method","detail":"(Integer) : boolean",
 *                      "doc":"ArrayDeque","insert":"offer($1)","snippet":true,"sort":"1offer"}]}
 */
public class LcLiveServer {

    static final int DEFAULT_PORT = 47321;

    /** Imports LeetCode adds implicitly to every Java submission. Kept on ONE line so user lines shift by exactly 1. */
    static final String IMPORTS =
            "import java.util.*; import java.util.stream.*; import java.math.*; import java.util.concurrent.*; "
          + "import java.util.function.*; import java.io.*; import java.text.*; import java.util.regex.*; "
          + "import java.lang.reflect.*;";

    /** Fallback stubs for LeetCode helper types, used only when the source doesn't define the type itself. */
    static final Map<String, String> STUBS = new LinkedHashMap<>();
    static {
        STUBS.put("ListNode",
            "class ListNode { int val; ListNode next; ListNode() {} ListNode(int val) { this.val = val; } "
          + "ListNode(int val, ListNode next) { this.val = val; this.next = next; } }");
        STUBS.put("TreeNode",
            "class TreeNode { int val; TreeNode left; TreeNode right; TreeNode() {} TreeNode(int val) { this.val = val; } "
          + "TreeNode(int val, TreeNode left, TreeNode right) { this.val = val; this.left = left; this.right = right; } }");
        STUBS.put("Node",
            "class Node { public int val; public Node left, right, next, prev, child, parent, random; "
          + "public List<Node> children = new ArrayList<>(); public List<Node> neighbors = new ArrayList<>(); "
          + "public Node() {} public Node(int v) { val = v; } public Node(int v, Node n) { val = v; next = n; } "
          + "public Node(int v, List<Node> c) { val = v; children = c; } "
          + "public Node(int v, Node l, Node r, Node n) { val = v; left = l; right = r; next = n; } }");
        STUBS.put("NestedInteger",
            "interface NestedInteger { boolean isInteger(); Integer getInteger(); void setInteger(int value); "
          + "void add(NestedInteger ni); List<NestedInteger> getList(); }");
        STUBS.put("GuessGame", "class GuessGame { int guess(int num) { return 0; } }");
        STUBS.put("VersionControl", "class VersionControl { boolean isBadVersion(int version) { return false; } }");
        STUBS.put("Relation", "class Relation { boolean knows(int a, int b) { return false; } }");
        STUBS.put("Reader4", "class Reader4 { int read4(char[] buf4) { return 0; } }");
        STUBS.put("MountainArray", "interface MountainArray { int get(int index); int length(); }");
        STUBS.put("ArrayReader", "interface ArrayReader { int get(int index); int length(); int compareSub(int l, int r, int x, int y); }");
        STUBS.put("BinaryMatrix", "interface BinaryMatrix { int get(int row, int col); List<Integer> dimensions(); }");
        STUBS.put("Master", "interface Master { int guess(String word); }");
        STUBS.put("Robot", "interface Robot { boolean move(); void turnLeft(); void turnRight(); void clean(); }");
        STUBS.put("Sea", "interface Sea { boolean hasShips(int[] topRight, int[] bottomLeft); }");
        STUBS.put("ImmutableListNode", "interface ImmutableListNode { void printValue(); ImmutableListNode getNext(); }");
        STUBS.put("Employee", "class Employee { public int id; public int importance; public List<Integer> subordinates; }");
        STUBS.put("Interval", "class Interval { public int start, end; public Interval() {} public Interval(int s, int e) { start = s; end = e; } }");
        STUBS.put("Point", "class Point { public int x, y; public Point() {} public Point(int a, int b) { x = a; y = b; } }");
    }

    /** javac options. should-stop.ifError=FLOW keeps attribution running after a syntax error, like an IDE. */
    static final List<String> OPTS = Arrays.asList(
            "-proc:none", "-Xmaxerrs", "200", "-Xlint:none", "-g:none", "-encoding", "UTF-8",
            "-XDshould-stop.ifError=FLOW");

    static final Pattern TYPE_DECL = Pattern.compile("\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");
    static final Pattern PUBLIC_TYPE = Pattern.compile("(?m)^\\s*public\\s+(?:final\\s+|abstract\\s+)*(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");
    static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    final JavaCompiler compiler;
    final StandardJavaFileManager stdFm;

    LcLiveServer() {
        compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler found. Run this with a JDK, not a JRE.");
        }
        stdFm = compiler.getStandardFileManager(null, Locale.ENGLISH, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- compile

    static final class Unit extends SimpleJavaFileObject {
        final String code;
        Unit(String name, String code) {
            super(URI.create("string:///" + name + ".java"), Kind.SOURCE);
            this.code = code;
        }
        @Override public CharSequence getCharContent(boolean ignore) { return code; }
    }

    static final class Sink extends SimpleJavaFileObject {
        Sink(String name, Kind kind) { super(URI.create("mem:///" + name.replace('.', '/') + kind.extension), kind); }
        @Override public OutputStream openOutputStream() { return new ByteArrayOutputStream(); }
    }

    /** Compile-unit assembled from a raw LeetCode editor buffer. */
    static final class Prepared {
        final List<JavaFileObject> units = new ArrayList<>();
        Unit main;
        int mainLineOffset;
    }

    static Prepared prepare(String code) {
        Prepared p = new Prepared();
        Set<String> declared = new HashSet<>();

        // 1. Types declared by the user (outside comments).
        String noComments = BLOCK_COMMENT.matcher(code).replaceAll(m -> "");
        Matcher m = TYPE_DECL.matcher(noComments);
        while (m.find()) declared.add(m.group(1));

        // 2. Definitions LeetCode ships inside the leading comment block, e.g.
        //    /** Definition for singly-linked list.  public class ListNode {...} */
        //    /* // Definition for a Node.  class Node {...}; */
        Matcher cm = BLOCK_COMMENT.matcher(code);
        while (cm.find()) {
            String body = uncomment(cm.group());
            Matcher dm = TYPE_DECL.matcher(body);
            while (dm.find()) {
                String name = dm.group(1);
                String decl = extractTypeBody(body, dm.start());
                if (decl == null || declared.contains(name)) continue;
                declared.add(name);
                // Drop 'public' so the unit name doesn't have to match the file name.
                decl = decl.replaceFirst("^(\\s*)public\\s+", "$1");
                p.units.add(new Unit(name, IMPORTS + "\n" + decl));
            }
        }

        // 3. Fallback stubs for anything still missing but referenced by name.
        for (Map.Entry<String, String> e : STUBS.entrySet()) {
            String name = e.getKey();
            if (declared.contains(name)) continue;
            if (!Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(noComments).find()) continue;
            declared.add(name);
            p.units.add(new Unit(name, IMPORTS + "\n" + e.getValue()));
        }

        // 4. The user's buffer itself. File name must match a public top-level type if there is one.
        String mainName = "Solution";
        Matcher pm = PUBLIC_TYPE.matcher(noComments);
        if (pm.find()) mainName = pm.group(1);
        p.main = new Unit(mainName, IMPORTS + "\n" + code);
        p.mainLineOffset = 1;
        p.units.add(p.main);
        return p;
    }

    /**
     * Given the offset of a type keyword inside plain text, return the declaration from the start of
     * that line through its matching closing brace (brace-balanced), or null if unbalanced.
     * This skips the prose LeetCode puts around the definition ("Definition for singly-linked list.").
     */
    static String extractTypeBody(String text, int kwOffset) {
        int lineStart = text.lastIndexOf('\n', kwOffset) + 1;
        int open = text.indexOf('{', kwOffset);
        if (open < 0) return null;
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return text.substring(lineStart, i + 1);
            }
        }
        return null;
    }

    /** Strip comment delimiters and per-line prefixes from a block comment, returning plain text. */
    static String uncomment(String comment) {
        String s = comment;
        if (s.startsWith("/**")) s = s.substring(3); else if (s.startsWith("/*")) s = s.substring(2);
        if (s.endsWith("*/")) s = s.substring(0, s.length() - 2);
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n", -1)) {
            String t = line.replaceFirst("^\\s*\\*\\s?", "");
            String trimmed = t.trim();
            if (trimmed.startsWith("//")) { sb.append('\n'); continue; } // keep line count, drop prose
            sb.append(t).append('\n');
        }
        return sb.toString();
    }

    JavaFileManager memFm() {
        return new ForwardingJavaFileManager<StandardJavaFileManager>(stdFm) {
            @Override public JavaFileObject getJavaFileForOutput(Location loc, String className, JavaFileObject.Kind kind, FileObject sibling) {
                return new Sink(className, kind);
            }
        };
    }

    synchronized String compile(String code) {
        long t0 = System.nanoTime();
        Prepared p = prepare(code);
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        JavaFileManager fm = memFm();
        Boolean ok;
        try {
            ok = compiler.getTask(new StringWriter(), fm, diags, OPTS, null, p.units).call();
        } catch (RuntimeException ex) {
            return json(false, 0, Collections.emptyList(), "compiler crashed: " + ex);
        }

        List<String> items = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.NOTE || d.getKind() == Diagnostic.Kind.OTHER) continue;
            JavaFileObject src = d.getSource();
            String message = d.getMessage(Locale.ENGLISH);
            long line, col, endLine, endCol;
            if (src != null && src.toUri().equals(p.main.toUri()) && d.getLineNumber() > 0) {
                line = d.getLineNumber() - p.mainLineOffset;
                col = Math.max(1, d.getColumnNumber());
                long[] end = endOf(p.main.code, d);
                endLine = end[0] - p.mainLineOffset;
                endCol = end[1];
                if (line < 1) { line = 1; col = 1; endLine = 1; endCol = 1; }
            } else {
                // Error inside a stub/extracted definition: pin it to line 1 and say where it came from.
                line = 1; col = 1; endLine = 1; endCol = 1;
                String where = src == null ? "?" : src.toUri().getPath().replaceFirst("^/", "");
                message = "[in " + where + "] " + message;
            }
            String sev = d.getKind() == Diagnostic.Kind.ERROR ? "error" : "warning";
            items.add("{\"line\":" + line + ",\"col\":" + col + ",\"endLine\":" + endLine + ",\"endCol\":" + endCol
                    + ",\"severity\":\"" + sev + "\",\"message\":\"" + esc(message) + "\"}");
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return json(Boolean.TRUE.equals(ok), ms, items, null);
    }

    /** Compute a 1-based [line, col] for the end of a diagnostic, extending zero-width ranges over the next token. */
    static long[] endOf(String code, Diagnostic<?> d) {
        long start = d.getStartPosition();
        long end = d.getEndPosition();
        if (start == Diagnostic.NOPOS) start = Math.max(0, d.getPosition());
        boolean widen = false;
        if (end == Diagnostic.NOPOS || end <= start) {
            int i = (int) start;
            while (i < code.length() && Character.isJavaIdentifierPart(code.charAt(i))) i++;
            end = i;
            widen = (end == start); // still zero-width: not on an identifier, widen by one column
        }
        long line = 1, col = 1;
        for (int i = 0; i < end && i < code.length(); i++) {
            if (code.charAt(i) == '\n') { line++; col = 1; } else col++;
        }
        if (widen) col++;
        return new long[]{line, col};
    }

    // ------------------------------------------------------------- completion

    static final int HEADER_LEN = IMPORTS.length() + 1; // user offset -> main-unit offset
    static final boolean DEBUG = System.getenv("LC_LIVE_DEBUG") != null;

    /** Packages LeetCode imports implicitly (plus java.lang); their public top-level types are offered by simple name. */
    static final String[] INDEX_PACKAGES = {
        "java.lang", "java.util", "java.util.stream", "java.util.function", "java.math",
        "java.util.concurrent", "java.util.concurrent.atomic", "java.io", "java.text", "java.util.regex", "java.lang.reflect" };

    static final String[] KEYWORDS = {
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "continue", "default", "do",
        "double", "else", "enum", "extends", "final", "finally", "float", "for", "if", "implements", "import", "instanceof",
        "int", "interface", "long", "native", "new", "null", "package", "private", "protected", "public", "return", "short",
        "static", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "true", "false", "try", "var",
        "void", "volatile", "while", "record", "yield" };

    /** {simpleName, package, kind}; built once at startup. */
    volatile List<String[]> classIndex = Collections.emptyList();

    synchronized void buildClassIndex() {
        long t0 = System.nanoTime();
        List<String[]> out = new ArrayList<>();
        try {
            JavacTask task = (JavacTask) compiler.getTask(new StringWriter(), memFm(), null, OPTS, null,
                    Collections.singletonList(new Unit("Idx", "class Idx {}")));
            task.analyze();
            Elements el = task.getElements();
            for (String pkg : INDEX_PACKAGES) {
                PackageElement pe = el.getPackageElement(pkg);
                if (pe == null) continue;
                for (Element e : pe.getEnclosedElements()) {
                    if (!(e instanceof TypeElement) || !e.getModifiers().contains(Modifier.PUBLIC)) continue;
                    out.add(new String[]{ e.getSimpleName().toString(), pkg, kindOf(e) });
                }
            }
        } catch (Throwable t) {
            System.err.println("class index failed: " + t);
        }
        out.sort(Comparator.comparing((String[] a) -> a[0]));
        classIndex = out;
        System.out.println("class index: " + out.size() + " types in " + (System.nanoTime() - t0) / 1_000_000 + "ms");
    }

    static String kindOf(Element e) {
        switch (e.getKind()) {
            case CLASS: return "class";
            case INTERFACE: case ANNOTATION_TYPE: return "interface";
            case ENUM: return "enum";
            case RECORD: return "record";
            case METHOD: return "method";
            case CONSTRUCTOR: return "constructor";
            case FIELD: return "field";
            case ENUM_CONSTANT: return "constant";
            case PACKAGE: return "package";
            default: return "variable";
        }
    }

    /** "java.util.Map<java.lang.Integer,java.util.List<java.lang.String>>" -> "Map<Integer,List<String>>" */
    static String simple(TypeMirror t) {
        return simple(String.valueOf(t));
    }
    static String simple(String s) {
        return s.replaceAll("\\b(?:[a-z_][\\w$]*\\.)+(?=[A-Z_$])", "");
    }

    static final class Item {
        final String label, kind, detail, doc, insert, sort; final boolean snippet;
        Item(String label, String kind, String detail, String doc, String insert, boolean snippet, String sort) {
            this.label = label; this.kind = kind; this.detail = detail; this.doc = doc; this.insert = insert; this.snippet = snippet; this.sort = sort;
        }
        String key() { return label + "|" + detail; }
        String json() {
            return "{\"label\":\"" + esc(label) + "\",\"kind\":\"" + kind + "\",\"detail\":\"" + esc(detail)
                 + "\",\"doc\":\"" + esc(doc) + "\",\"insert\":\"" + esc(insert) + "\",\"snippet\":" + snippet
                 + ",\"sort\":\"" + esc(sort) + "\"}";
        }
    }

    synchronized String complete(String code, int caret) {
        long t0 = System.nanoTime();
        caret = Math.max(0, Math.min(caret, code.length()));
        int start = caret;
        while (start > 0 && Character.isJavaIdentifierPart(code.charAt(start - 1))) start--;
        String prefix = code.substring(start, caret);
        int k = start - 1;
        while (k >= 0 && (code.charAt(k) == ' ' || code.charAt(k) == '\t')) k--;
        boolean memberMode = k >= 0 && code.charAt(k) == '.';
        if (inCommentOrString(code, start)) return completionJson(true, t0, Collections.emptyList(), false);

        Prepared p = prepare(code);
        LinkedHashMap<String, Item> items = new LinkedHashMap<>();
        boolean incomplete = false;
        try {
            JavacTask task = (JavacTask) compiler.getTask(new StringWriter(), memFm(), d -> {}, OPTS, null, p.units);
            CompilationUnitTree unit = null;
            // javac wraps client file objects, so compare by URI rather than identity.
            for (CompilationUnitTree cu : task.parse()) if (cu.getSourceFile().toUri().equals(p.main.toUri())) unit = cu;
            try { task.analyze(); } catch (Throwable t) { if (DEBUG) System.err.println("analyze: " + t); }
            if (DEBUG) System.err.println("complete: memberMode=" + memberMode + " prefix='" + prefix + "' unit=" + (unit != null));
            if (unit != null) {
                Completer c = new Completer(task, unit, p.main.code);
                if (memberMode) c.members(k + HEADER_LEN, start + HEADER_LEN, items);
                else incomplete = c.scope(start + HEADER_LEN, prefix, items);
            }
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + esc("completion failed: " + t) + "\",\"items\":[]}";
        }
        return completionJson(true, t0, new ArrayList<>(items.values()), incomplete);
    }

    static String completionJson(boolean ok, long t0, List<Item> items, boolean incomplete) {
        StringBuilder sb = new StringBuilder("{\"ok\":").append(ok).append(",\"ms\":").append((System.nanoTime() - t0) / 1_000_000)
                .append(",\"incomplete\":").append(incomplete).append(",\"items\":[");
        for (int i = 0; i < items.size(); i++) { if (i > 0) sb.append(','); sb.append(items.get(i).json()); }
        return sb.append("]}").toString();
    }

    /** Cheap scan: is offset inside a comment or string/char literal? */
    static boolean inCommentOrString(String s, int offset) {
        boolean line = false, block = false, str = false, chr = false;
        for (int i = 0; i < offset && i < s.length(); i++) {
            char c = s.charAt(i), n = i + 1 < s.length() ? s.charAt(i + 1) : 0;
            if (line) { if (c == '\n') line = false; }
            else if (block) { if (c == '*' && n == '/') { block = false; i++; } }
            else if (str) { if (c == '\\') i++; else if (c == '"' || c == '\n') str = false; }
            else if (chr) { if (c == '\\') i++; else if (c == '\'' || c == '\n') chr = false; }
            else if (c == '/' && n == '/') { line = true; i++; }
            else if (c == '/' && n == '*') { block = true; i++; }
            else if (c == '"') str = true;
            else if (c == '\'') chr = true;
        }
        return line || block || str || chr;
    }

    /** Walks the attributed tree of the user's unit to produce completion items. */
    final class Completer {
        final CompilationUnitTree unit; final Trees trees; final SourcePositions sp; final Types types; final Elements elements; final String src;

        Completer(JavacTask task, CompilationUnitTree unit, String src) {
            this.unit = unit; this.trees = Trees.instance(task); this.sp = trees.getSourcePositions();
            this.types = task.getTypes(); this.elements = task.getElements(); this.src = src;
        }

        /** Innermost tree path containing pos. */
        TreePath pathAt(long pos) {
            TreePath[] best = { new TreePath(unit) };
            new TreePathScanner<Void, Void>() {
                @Override public Void scan(Tree tree, Void v) {
                    if (tree == null) return null;
                    long s = sp.getStartPosition(unit, tree), e = sp.getEndPosition(unit, tree);
                    if (s != Diagnostic.NOPOS && s <= pos && (e == Diagnostic.NOPOS || pos <= e)) {
                        TreePath prev = getCurrentPath();
                        best[0] = new TreePath(prev == null ? new TreePath(unit) : prev, tree);
                        return super.scan(tree, v);
                    }
                    return null;
                }
                @Override public Void visitErroneous(ErroneousTree node, Void v) {
                    // Default TreeScanner skips error subtrees; a statement missing its ';' lives there.
                    return scan(node.getErrorTrees(), v);
                }
            }.scan(unit, null);
            return best[0];
        }

        /** Completion after a '.', where dot is the offset of the '.' and caretStart the start of the typed prefix. */
        void members(long dot, long caretStart, Map<String, Item> out) {
            // Record the path ourselves: Trees.getPath() won't descend into erroneous subtrees, and a
            // half-typed "dq." statement (no ';' yet) is exactly such a subtree.
            TreePath[] bestPath = { null }; long[] bestEnd = { -1 };
            new TreePathScanner<Void, Void>() {
                @Override public Void visitMemberSelect(MemberSelectTree t, Void v) {
                    long e = sp.getEndPosition(unit, t.getExpression()), s = sp.getStartPosition(unit, t);
                    if (e != Diagnostic.NOPOS && s >= HEADER_LEN && e <= dot && s <= dot && e > bestEnd[0]) { bestPath[0] = getCurrentPath(); bestEnd[0] = e; }
                    return super.visitMemberSelect(t, v);
                }
                @Override public Void visitErroneous(ErroneousTree node, Void v) { return scan(node.getErrorTrees(), v); }
            }.scan(unit, null);
            if (DEBUG) System.err.println("members: dot=" + dot + " best=" + (bestPath[0] == null ? null : bestPath[0].getLeaf()));
            if (bestPath[0] == null) return;
            MemberSelectTree best = (MemberSelectTree) bestPath[0].getLeaf();
            TreePath exprPath = new TreePath(bestPath[0], best.getExpression());
            Element exprEl = trees.getElement(exprPath);
            TypeMirror tm = trees.getTypeMirror(exprPath);
            if (DEBUG) System.err.println("members: el=" + exprEl + " type=" + tm);
            com.sun.source.tree.Scope scope = trees.getScope(pathAt(caretStart));

            if (exprEl != null && exprEl.getKind() == ElementKind.PACKAGE) {
                for (Element e : exprEl.getEnclosedElements())
                    if (e.getModifiers().contains(Modifier.PUBLIC)) put(out, new Item(name(e), kindOf(e), "", exprEl.toString(), name(e), false, "1" + name(e)));
                return;
            }
            if (tm == null) return;
            if (tm.getKind() == TypeKind.TYPEVAR) tm = ((TypeVariable) tm).getUpperBound();
            if (tm.getKind() == TypeKind.ARRAY) {
                put(out, new Item("length", "field", "int", "array", "length", false, "0length"));
                tm = elements.getTypeElement("java.lang.Object").asType();
            }
            if (tm.getKind() != TypeKind.DECLARED) return;
            DeclaredType dt = (DeclaredType) tm;
            TypeElement te = (TypeElement) dt.asElement();
            boolean staticOnly = exprEl != null && (exprEl.getKind().isClass() || exprEl.getKind().isInterface());
            if (staticOnly) put(out, new Item("class", "field", "Class<" + te.getSimpleName() + ">", "", "class", false, "9class"));
            for (Element m : elements.getAllMembers(te)) {
                ElementKind mk = m.getKind();
                if (mk == ElementKind.CONSTRUCTOR || mk == ElementKind.STATIC_INIT || mk == ElementKind.INSTANCE_INIT) continue;
                if (mk != ElementKind.METHOD && mk != ElementKind.FIELD && mk != ElementKind.ENUM_CONSTANT && !mk.isClass() && !mk.isInterface()) continue;
                boolean isStatic = m.getModifiers().contains(Modifier.STATIC) || mk == ElementKind.ENUM_CONSTANT || mk.isClass() || mk.isInterface();
                if (staticOnly != isStatic) continue;
                if (scope != null && !trees.isAccessible(scope, m, dt)) continue;
                if (name(m).startsWith("<")) continue;
                String sortPrefix = m.getEnclosingElement().equals(te) ? "1" : (isObject(m.getEnclosingElement()) ? "3" : "2");
                put(out, item(m, dt, sortPrefix));
            }
        }

        /** Completion of a bare identifier: locals, enclosing-class members, well-known types, keywords. Returns incomplete flag. */
        boolean scope(long pos, String prefix, Map<String, Item> out) {
            TreePath path = pathAt(pos);
            com.sun.source.tree.Scope scope = trees.getScope(path);
            String lp = prefix.toLowerCase(Locale.ROOT);
            TypeElement enclosing = scope == null ? null : scope.getEnclosingClass();
            if (DEBUG) System.err.println("scope: leaf=" + path.getLeaf().getKind() + " enclosing=" + enclosing + " index=" + classIndex.size());
            for (com.sun.source.tree.Scope s = scope; s != null && s.getEnclosingClass() != null; s = s.getEnclosingScope()) {
                for (Element e : s.getLocalElements()) {
                    String n = name(e);
                    if (n.startsWith("<") || n.isEmpty() || n.equals("this") || n.equals("super")) continue;
                    if (!matches(n, lp)) continue;
                    ElementKind ek = e.getKind();
                    if (ek == ElementKind.CONSTRUCTOR || ek == ElementKind.STATIC_INIT || ek == ElementKind.INSTANCE_INIT) continue;
                    boolean local = ek == ElementKind.LOCAL_VARIABLE || ek == ElementKind.PARAMETER || ek == ElementKind.EXCEPTION_PARAMETER
                            || ek == ElementKind.RESOURCE_VARIABLE || ek == ElementKind.BINDING_VARIABLE;
                    put(out, item(e, null, local ? "0" : isObject(e.getEnclosingElement()) ? "3" : "1"));
                }
            }
            if (enclosing != null) {
                DeclaredType dt = (DeclaredType) enclosing.asType();
                for (Element m : elements.getAllMembers(enclosing)) {
                    ElementKind mk = m.getKind();
                    if (mk != ElementKind.METHOD && mk != ElementKind.FIELD && mk != ElementKind.ENUM_CONSTANT && !mk.isClass() && !mk.isInterface()) continue;
                    if (name(m).startsWith("<") || !matches(name(m), lp)) continue;
                    if (scope != null && !trees.isAccessible(scope, m, dt)) continue;
                    String sortPrefix = isObject(m.getEnclosingElement()) ? "3" : "1";
                    put(out, item(m, dt, sortPrefix));
                }
            }
            if (lp.isEmpty()) return true; // too broad to list every JDK type; client will re-query once a letter is typed
            for (String[] c : classIndex) {
                if (!matches(c[0], lp)) continue;
                String pri = (c[1].equals("java.util") || c[1].equals("java.lang")) ? "2" : "4";
                put(out, new Item(c[0], c[2], c[1], c[1] + "." + c[0], c[0], false, pri + c[0]));
            }
            for (String kw : KEYWORDS) if (kw.startsWith(lp)) put(out, new Item(kw, "keyword", "", "", kw, false, "5" + kw));
            return false;
        }

        boolean matches(String name, String lp) {
            if (lp.isEmpty()) return true;
            String ln = name.toLowerCase(Locale.ROOT);
            if (ln.startsWith(lp)) return true;
            // camel-hump match: "ade" -> ArrayDeque, "hm" -> HashMap. Needs 2+ chars and a matching first letter.
            if (lp.length() < 2 || Character.toLowerCase(name.charAt(0)) != lp.charAt(0)) return false;
            int j = 0;
            for (int i = 0; i < name.length() && j < lp.length(); i++) {
                char c = name.charAt(i);
                if (Character.isUpperCase(c) || i == 0) { if (Character.toLowerCase(c) == lp.charAt(j)) j++; }
            }
            return j == lp.length();
        }

        Item item(Element e, DeclaredType owner, String sortPrefix) {
            String n = name(e);
            ElementKind ek = e.getKind();
            String doc = ek.isClass() || ek.isInterface() ? e.toString() : name(e.getEnclosingElement());
            if (ek == ElementKind.METHOD) {
                ExecutableType et = null;
                try { et = (ExecutableType) (owner != null ? types.asMemberOf(owner, e) : e.asType()); } catch (RuntimeException ignore) {}
                ExecutableElement ee = (ExecutableElement) e;
                StringBuilder params = new StringBuilder("(");
                List<? extends VariableElement> ps = ee.getParameters();
                for (int i = 0; i < ps.size(); i++) {
                    if (i > 0) params.append(", ");
                    TypeMirror pt = et != null && i < et.getParameterTypes().size() ? et.getParameterTypes().get(i) : ps.get(i).asType();
                    String pn = ps.get(i).getSimpleName().toString();
                    params.append(simple(pt));
                    if (!pn.matches("arg\\d+")) params.append(' ').append(pn);
                }
                params.append(')');
                String ret = simple(et != null ? et.getReturnType() : ee.getReturnType());
                boolean hasParams = !ps.isEmpty();
                return new Item(n, "method", params + " : " + ret, doc, hasParams ? n + "($1)" : n + "()", hasParams, sortPrefix + n);
            }
            String type;
            try { type = simple(owner != null && (ek == ElementKind.FIELD || ek == ElementKind.ENUM_CONSTANT) ? types.asMemberOf(owner, e) : e.asType()); }
            catch (RuntimeException ex) { type = simple(e.asType()); }
            return new Item(n, kindOf(e), type, doc, n, false, sortPrefix + n);
        }

        static String name(Element e) { return e.getSimpleName().toString(); }
        static boolean isObject(Element e) { return e instanceof TypeElement && ((TypeElement) e).getQualifiedName().contentEquals("java.lang.Object"); }
        void put(Map<String, Item> out, Item it) { out.putIfAbsent(it.key(), it); }
    }

    // ------------------------------------------------------------------- json

    static String json(boolean ok, long ms, List<String> items, String error) {
        StringBuilder sb = new StringBuilder("{\"ok\":").append(ok).append(",\"ms\":").append(ms);
        if (error != null) sb.append(",\"error\":\"").append(esc(error)).append('"');
        sb.append(",\"diagnostics\":[").append(String.join(",", items)).append("]}");
        return sb.toString();
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------- http

    static void cors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-Lc-Offset");
        ex.getResponseHeaders().set("Access-Control-Allow-Private-Network", "true");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
    }

    static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        // Bind address: 127.0.0.1 by default. In Docker set LC_LIVE_HOST=0.0.0.0 and publish the port to host loopback only.
        String host = System.getenv().getOrDefault("LC_LIVE_HOST", "127.0.0.1");
        LcLiveServer srv = new LcLiveServer();
        String javaVersion = System.getProperty("java.version");

        // Warm up javac so the first real keystroke doesn't pay JIT cost.
        srv.compile("class Solution { public int[] twoSum(int[] nums, int target) { Map<Integer,Integer> m = new HashMap<>(); return new int[0]; } }");

        HttpServer http = HttpServer.create(new InetSocketAddress(host, port), 16);
        http.createContext("/health", ex -> {
            cors(ex);
            if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); ex.close(); return; }
            send(ex, 200, "{\"ok\":true,\"service\":\"lc-live\",\"java\":\"" + esc(javaVersion) + "\",\"languages\":[\"java\"],\"features\":[\"diagnostics\",\"completion\"],\"indexedTypes\":" + srv.classIndex.size() + "}");
        });
        http.createContext("/compile", ex -> {
            cors(ex);
            try {
                if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }
                if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "{\"ok\":false,\"error\":\"POST only\"}"); return; }
                String code = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (code.length() > 512 * 1024) { send(ex, 413, "{\"ok\":false,\"error\":\"source too large\"}"); return; }
                send(ex, 200, srv.compile(code));
            } catch (Exception e) {
                send(ex, 500, "{\"ok\":false,\"error\":\"" + esc(String.valueOf(e)) + "\"}");
            } finally {
                ex.close();
            }
        });
        http.createContext("/complete", ex -> {
            cors(ex);
            try {
                if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }
                if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "{\"ok\":false,\"error\":\"POST only\"}"); return; }
                String code = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (code.length() > 512 * 1024) { send(ex, 413, "{\"ok\":false,\"error\":\"source too large\"}"); return; }
                String off = ex.getRequestHeaders().getFirst("X-Lc-Offset");
                int caret = off == null ? code.length() : Integer.parseInt(off.trim());
                send(ex, 200, srv.complete(code, caret));
            } catch (Exception e) {
                send(ex, 500, "{\"ok\":false,\"error\":\"" + esc(String.valueOf(e)) + "\"}");
            } finally {
                ex.close();
            }
        });
        http.setExecutor(Executors.newFixedThreadPool(2));
        http.start();
        new Thread(srv::buildClassIndex, "lc-live-index").start();
        System.out.println("lc-live server listening on http://" + host + ":" + port + "  (java " + javaVersion + ")");
    }
}
