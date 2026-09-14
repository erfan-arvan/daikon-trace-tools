package tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * Parses a Daikon Chicory {@code .dtrace} (or {@code .dtrace.gz}) trace file and produces a JSON
 * index of concrete input/output examples per method, keyed in the same {@code
 * pkg.Class#name(paramTypes):returnType} format daikonplusplus itself uses (matching {@code
 * ProgramElementId.toString()} / {@code MethodSignatureUtil.jvmDescriptorBestEffort}).
 *
 * <p>No Daikon invariant inference is involved -- this only reads the raw trace records Chicory
 * already produced from a real test run, pairing each method-entry record with its matching exit
 * record via Daikon's {@code this_invocation_nonce} field.
 *
 * <p>Reads and processes the trace one block (blank-line-separated paragraph) at a time rather
 * than materializing the whole file as {@code List<List<String>>} first -- traces from
 * array-heavy code can run into the hundreds of GB, far past what fits in any heap as Java
 * objects all at once. Individual lines are also read through a bounded reader: Daikon's dtrace
 * format writes array-valued variables element-by-element inline on one line, so a single
 * variable holding a large array can itself be many GB long. Bounding the read means a
 * pathological line gets truncated (documented as such in the output) instead of failing the
 * whole extraction with an OutOfMemoryError from a single {@code String} allocation.
 */
public class IOExamplesExtractor {

  private static final Pattern SAMPLE_HEADER = Pattern.compile("^(.*):::(ENTER|EXIT\\d*)$");

  /** Maximum number of examples retained per method in the output JSON. */
  private static final int MAX_EXAMPLES_PER_METHOD = 20;

  /**
   * Maximum characters kept per line. Daikon dumps array-valued variables inline on one line, so
   * this is a real limit some lines legitimately hit, not just a safety margin -- lines past this
   * are truncated (with a marker appended) rather than fully materialized.
   */
  private static final int MAX_LINE_CHARS = 1_000_000;

  public static void main(String[] args) throws Exception {
    Map<String, String> opts = parseArgs(args);
    String dtracePath = require(opts, "dtrace");
    String srcRoot = require(opts, "src");
    String outPath = require(opts, "out");

    System.out.println("[io-examples-tool] dtrace=" + dtracePath);
    System.out.println("[io-examples-tool] src=" + srcRoot);
    System.out.println("[io-examples-tool] out=" + outPath);

    TraceCollector collector = new TraceCollector();

    try (BufferedReader reader = openReader(dtracePath)) {
      List<String> current = new ArrayList<>();
      String line;
      while ((line = readBoundedLine(reader)) != null) {
        if (line.isBlank()) {
          if (!current.isEmpty()) {
            collector.processBlock(current);
            current = new ArrayList<>();
          }
        } else {
          current.add(line);
        }
      }
      if (!current.isEmpty()) {
        collector.processBlock(current);
      }
    }

    System.out.println("[io-examples-tool] ENTER records=" + collector.enterCount);
    System.out.println("[io-examples-tool] paired ENTER/EXIT examples=" + collector.pairedCount);
    System.out.println("[io-examples-tool] truncated lines=" + truncatedLineTotal);
    System.out.println("[io-examples-tool] distinct program points=" + collector.byPpt.size());

    System.out.println("[io-examples-tool] scanning source tree to build forward index...");
    ForwardSourceIndex forwardIndex = ForwardSourceIndex.build(Path.of(srcRoot));
    System.out.println(
        "[io-examples-tool] forward index built: " + forwardIndex.size() + " declared members");

    Map<String, List<Map<String, Object>>> index = new LinkedHashMap<>();
    int resolved = 0;
    int unresolved = 0;

    for (Map.Entry<String, List<Map<String, Object>>> e : collector.byPpt.entrySet()) {
      String key = forwardIndex.resolve(e.getKey());
      if (key == null) {
        unresolved++;
        continue;
      }
      resolved++;
      index.put(key, e.getValue());
    }

    System.out.println("[io-examples-tool] resolved program points=" + resolved);
    System.out.println("[io-examples-tool] unresolved program points=" + unresolved);

    ObjectMapper mapper = new ObjectMapper();
    mapper.writerWithDefaultPrettyPrinter().writeValue(new File(outPath), index);
    System.out.println("[io-examples-tool] wrote " + outPath);
  }

  /**
   * Holds the ENTER/EXIT pairing state across the whole streamed pass, and processes one
   * blank-line-separated block (a declaration, or a data sample) at a time.
   */
  private static class TraceCollector {
    // pptBaseName (e.g. "com.example.MathUtils.add(int, int)") -> list of {args, return} records
    final Map<String, List<Map<String, Object>>> byPpt = new LinkedHashMap<>();

    // nonce -> args recorded at the matching ENTER, awaiting its EXIT
    final Map<String, Map<String, String>> pendingEntries = new LinkedHashMap<>();

    int enterCount = 0;
    int pairedCount = 0;

    void processBlock(List<String> block) {
      String header = block.get(0);
      if (header.startsWith("ppt ")) return; // declaration block, not a data sample

      Matcher m = SAMPLE_HEADER.matcher(header);
      if (!m.matches()) return; // preamble (decl-version, var-comparability, ...)

      String pptBaseName = m.group(1);
      boolean isEnter = m.group(2).equals("ENTER");

      Map<String, String> vars = parseVarTriples(block.subList(1, block.size()));
      String nonce = vars.get("this_invocation_nonce");
      if (nonce == null) return;

      if (isEnter) {
        enterCount++;
        Map<String, String> entryArgs = new LinkedHashMap<>(vars);
        entryArgs.remove("this_invocation_nonce");
        pendingEntries.put(nonce, entryArgs);
      } else {
        Map<String, String> entryArgs = pendingEntries.remove(nonce);
        if (entryArgs == null) return; // EXIT with no matching ENTER (truncated trace, etc.)

        List<Map<String, Object>> examples = byPpt.computeIfAbsent(pptBaseName, k -> new ArrayList<>());
        if (examples.size() >= MAX_EXAMPLES_PER_METHOD) return;

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("args", entryArgs);
        record.put("return", vars.get("return"));
        examples.add(record);
        pairedCount++;
      }
    }
  }

  /** Parses a data sample's body (name/value/mod-bit triples) into a var-name -> value map. */
  private static Map<String, String> parseVarTriples(List<String> lines) {
    Map<String, String> vars = new LinkedHashMap<>();
    int i = 0;
    // this_invocation_nonce is a special 2-line variable (name + value only, no mod-bit line) --
    // every other variable is a 3-line (name, value, mod-bit) triple. Without special-casing this,
    // a fixed stride-3 loop starting at line 0 stays permanently off by one for the rest of the
    // block: each (name, value, mod-bit) triple gets read as (value, mod-bit, next-name), so the
    // map ends up keyed by values instead of names, and the final variable in the block is dropped
    // entirely because the misalignment consumes one variable's worth of lines off the end.
    if (i + 1 < lines.size() && lines.get(i).equals("this_invocation_nonce")) {
      vars.put(lines.get(i), lines.get(i + 1));
      i += 2;
    }
    for (; i + 2 < lines.size(); i += 3) {
      String name = lines.get(i);
      String value = lines.get(i + 1);
      // mod-bit at lines.get(i + 2) is intentionally ignored
      vars.put(name, value);
    }
    return vars;
  }

  private static BufferedReader openReader(String path) throws IOException {
    InputStream in = new FileInputStream(path);
    if (path.endsWith(".gz")) {
      in = new GZIPInputStream(in);
    }
    return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
  }

  /**
   * Reads one line, same contract as {@link BufferedReader#readLine()} (returns {@code null} at
   * EOF, strips the line terminator, treats \n/\r\n/\r as terminators) except length-bounded:
   * once a line exceeds {@link #MAX_LINE_CHARS}, the rest of it is drained and discarded (never
   * appended to the returned string) rather than being fully materialized, and a truncation
   * marker is appended so the caller can tell the value is incomplete. This is what actually
   * fixes the OutOfMemoryError a plain {@code readLine()} hits on a single huge array-valued
   * line -- no heap size fixes a single contiguous allocation that's itself gigabytes long.
   */
  private static String readBoundedLine(BufferedReader reader) throws IOException {
    StringBuilder sb = null;
    boolean truncated = false;
    boolean sawAnyChar = false;

    int c;
    while ((c = reader.read()) != -1) {
      sawAnyChar = true;
      if (c == '\n') {
        break;
      }
      if (c == '\r') {
        reader.mark(1);
        int next = reader.read();
        if (next != '\n' && next != -1) {
          reader.reset();
        }
        break;
      }
      if (sb == null) sb = new StringBuilder();
      if (sb.length() < MAX_LINE_CHARS) {
        sb.append((char) c);
      } else {
        truncated = true;
        // intentionally not appended -- draining the rest of this line without retaining it
        // is the whole point: an oversized line must never be held in memory in full.
      }
    }

    if (!sawAnyChar) return null; // true EOF, no partial line pending

    String result = sb == null ? "" : sb.toString();
    if (truncated) {
      truncatedLineTotal++;
      result = result + " ...<truncated: line exceeded " + MAX_LINE_CHARS + " chars>";
    }
    return result;
  }

  // Static counter mirrored into TraceCollector.truncatedLineCount at the end of main(); kept
  // here (not as a TraceCollector field) because readBoundedLine() is called before any
  // TraceCollector block is available to attribute it to.
  private static long truncatedLineTotal = 0;

  private static Map<String, String> parseArgs(String[] args) {
    Map<String, String> m = new java.util.HashMap<>();
    for (int i = 0; i < args.length; i++) {
      if (args[i].startsWith("--") && i + 1 < args.length) {
        m.put(args[i].substring(2), args[i + 1]);
        i++;
      }
    }
    return m;
  }

  private static String require(Map<String, String> m, String key) {
    String v = m.get(key);
    if (v == null) throw new IllegalArgumentException("missing --" + key);
    return v;
  }

  /**
   * Resolves Daikon program-point base names (e.g. {@code
   * "com.example.Outer$Inner.method(java.lang.String,int)"}) to the {@code
   * pkg.Class#name(paramTypes):returnType} key format daikonplusplus uses.
   *
   * <p>This is a forward index, not a reverse lookup: the whole source tree is scanned once,
   * up front, and every declared method AND constructor (the earlier reverse-parsing resolver
   * only ever considered methods, so every constructor ppt was unconditionally unresolved) is
   * registered under a signature comparable against Daikon's own ppt strings -- fully-qualified
   * declaring class (with {@code $} for nesting, matching Chicory's own naming), member name, and
   * a simplified (package-stripped, generics-erased) parameter-type list. Trace-side ppt names are
   * normalized the same way before lookup, so matching is an exact map lookup, not string-splitting
   * a file path back out of the trace and hoping it corresponds to a real file (the previous
   * SourceKeyResolver's approach, which broke on nested classes, and always broke on constructors).
   *
   * <p>Known remaining gap: lambdas and anonymous classes have no source-declared node at all
   * (Daikon reports synthetic names like {@code Foo$$Lambda/0x...} or {@code Foo$1}), so they
   * can never appear in this index regardless of resolution strategy -- there is nothing in the
   * .java text to enumerate. Overload ambiguity is also only reduced, not eliminated: two
   * overloads whose parameters have the same simple type names from different packages would
   * collide (rare in practice); this index also does not attempt full symbol resolution.
   */
  static class ForwardSourceIndex {
    // "qualifiedClass$WithDollar#member(paramCount)|simpleType1,simpleType2,..." -> final key
    private final Map<String, String> byTraceSignature = new LinkedHashMap<>();

    static ForwardSourceIndex build(Path srcRoot) throws IOException {
      ForwardSourceIndex index = new ForwardSourceIndex();
      try (var stream = Files.walk(srcRoot)) {
        stream
            .filter(p -> p.toString().endsWith(".java"))
            .forEach(
                file -> {
                  try {
                    CompilationUnit cu = StaticJavaParser.parse(file);
                    String pkg =
                        cu.getPackageDeclaration().map(pd -> pd.getName().asString()).orElse("");
                    cu.findAll(ClassOrInterfaceDeclaration.class)
                        .forEach(
                            cls -> {
                              String qualifiedClass = qualifiedNameWithDollar(pkg, cls);
                              cls.getMethods().forEach(md -> index.addMethod(qualifiedClass, md));
                              cls.getConstructors()
                                  .forEach(cd -> index.addConstructor(qualifiedClass, cd));
                            });
                  } catch (Exception e) {
                    // skip unparsable file, same tolerance as the rest of the pipeline
                  }
                });
      }
      return index;
    }

    int size() {
      return byTraceSignature.size();
    }

    /** Builds "pkg.Outer$Inner" by walking up through enclosing type declarations. */
    private static String qualifiedNameWithDollar(String pkg, ClassOrInterfaceDeclaration cls) {
      List<String> parts = new ArrayList<>();
      parts.add(cls.getNameAsString());
      Node parent = cls.getParentNode().orElse(null);
      while (parent instanceof ClassOrInterfaceDeclaration) {
        parts.add(0, ((ClassOrInterfaceDeclaration) parent).getNameAsString());
        parent = parent.getParentNode().orElse(null);
      }
      String qualified = String.join("$", parts);
      return pkg.isEmpty() ? qualified : pkg + "." + qualified;
    }

    private static String innermostSimpleName(String qualifiedClassWithDollar) {
      int lastDot = qualifiedClassWithDollar.lastIndexOf('.');
      String simple =
          lastDot < 0 ? qualifiedClassWithDollar : qualifiedClassWithDollar.substring(lastDot + 1);
      int lastDollar = simple.lastIndexOf('$');
      return lastDollar < 0 ? simple : simple.substring(lastDollar + 1);
    }

    /**
     * Strips generics and package-qualification down to a bare simple-name form comparable
     * against Daikon's fully-qualified, erased runtime type strings -- e.g. {@code "List<String>"}
     * (source) and {@code "java.util.List"} (trace) both simplify to {@code "List"}; a varargs
     * parameter simplifies the same way its array-typed trace counterpart would ({@code
     * "String..."} -> {@code "String[]"}).
     */
    private static String simplifyType(String syntacticType, boolean isVarArgs) {
      String t = syntacticType.trim();
      int lt = t.indexOf('<');
      if (lt >= 0) t = t.substring(0, lt).trim();
      if (isVarArgs) t = t + "[]";

      String arraySuffix = "";
      while (t.endsWith("[]")) {
        arraySuffix += "[]";
        t = t.substring(0, t.length() - 2).trim();
      }
      int lastDot = t.lastIndexOf('.');
      if (lastDot >= 0) t = t.substring(lastDot + 1);
      return t + arraySuffix;
    }

    private static String traceSignature(
        String qualifiedClass, String memberName, List<String> simpleParamTypes) {
      return qualifiedClass
          + "#"
          + memberName
          + "("
          + simpleParamTypes.size()
          + ")|"
          + String.join(",", simpleParamTypes);
    }

    private void addMethod(String qualifiedClass, MethodDeclaration md) {
      List<String> simpleParamTypes = simplifyParams(md.getParameters());
      String signature = traceSignature(qualifiedClass, md.getNameAsString(), simpleParamTypes);

      String syntacticParams =
          md.getParameters().stream().map(p -> p.getType().toString()).collect(Collectors.joining(","));
      String finalKey =
          buildFinalKey(
              qualifiedClass, md.getNameAsString(), syntacticParams, md.getType().toString());

      byTraceSignature.putIfAbsent(signature, finalKey);
    }

    private void addConstructor(String qualifiedClass, ConstructorDeclaration cd) {
      // A constructor's name in javaparser's AST is already the enclosing class's simple name,
      // which is exactly how Chicory/Daikon report it in the ppt string too (e.g. "Foo.Foo(...)").
      String ctorName = cd.getNameAsString();
      List<String> simpleParamTypes = simplifyParams(cd.getParameters());
      String signature = traceSignature(qualifiedClass, ctorName, simpleParamTypes);

      String syntacticParams =
          cd.getParameters().stream().map(p -> p.getType().toString()).collect(Collectors.joining(","));
      // daikonplusplus's own JavaProjectScanner does not currently scan constructors as program
      // points at all, so there is no live "return type" convention for them to match -- "void"
      // is a placeholder, not a claim that this key is presently queryable by the live tool.
      String finalKey = buildFinalKey(qualifiedClass, ctorName, syntacticParams, "void");

      byTraceSignature.putIfAbsent(signature, finalKey);
    }

    private static List<String> simplifyParams(List<Parameter> params) {
      List<String> out = new ArrayList<>();
      for (Parameter p : params) {
        out.add(simplifyType(p.getType().toString(), p.isVarArgs()));
      }
      return out;
    }

    private static String buildFinalKey(
        String qualifiedClass, String memberName, String syntacticParams, String syntacticReturn) {
      int lastDot = qualifiedClass.lastIndexOf('.');
      String pkg = lastDot < 0 ? "" : qualifiedClass.substring(0, lastDot);
      String prefix = pkg.isEmpty() ? "" : pkg + ".";
      String innermost = innermostSimpleName(qualifiedClass);
      return prefix + innermost + "#" + memberName + "(" + syntacticParams + "):" + syntacticReturn;
    }

    /**
     * Given a raw Daikon ppt base name, looks up the matching daikonplusplus key, or {@code null}
     * if nothing declared in the scanned source matches (e.g. a lambda/anonymous class, or a
     * class outside the scanned {@code --src} root).
     */
    String resolve(String pptBaseName) {
      int paren = pptBaseName.indexOf('(');
      int closeParen = pptBaseName.lastIndexOf(')');
      if (paren < 0 || closeParen < paren) return null;

      String beforeParen = pptBaseName.substring(0, paren);
      String paramsPart = pptBaseName.substring(paren + 1, closeParen);

      int lastDot = beforeParen.lastIndexOf('.');
      if (lastDot < 0) return null;

      String qualifiedClass = beforeParen.substring(0, lastDot);
      String memberName = beforeParen.substring(lastDot + 1);

      List<String> simpleParamTypes =
          paramsPart.isBlank()
              ? List.of()
              : Arrays.stream(paramsPart.split(","))
                  .map(String::trim)
                  .map(t -> simplifyType(t, false))
                  .collect(Collectors.toList());

      String signature = traceSignature(qualifiedClass, memberName, simpleParamTypes);
      return byTraceSignature.get(signature);
    }
  }
}
