package tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

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

    SourceKeyResolver resolver = new SourceKeyResolver(Path.of(srcRoot));
    Map<String, List<Map<String, Object>>> index = new LinkedHashMap<>();
    int resolved = 0;
    int unresolved = 0;

    for (Map.Entry<String, List<Map<String, Object>>> e : collector.byPpt.entrySet()) {
      String key = resolver.resolveKey(e.getKey());
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
   * Resolves a Daikon program-point base name (e.g. {@code "com.example.MathUtils.add(int, int)"})
   * to the {@code pkg.Class#name(paramTypes):returnType} key format daikonplusplus uses, by
   * locating the method in source and rebuilding the descriptor the same way
   * MethodSignatureUtil.jvmDescriptorBestEffort does.
   *
   * <p>Like the call-site tool's equivalent resolver, this matches by declaring (simple) class name
   * and parameter count -- it does not attempt to resolve nested/inner classes whose file name
   * differs from the class's own simple name.
   */
  static class SourceKeyResolver {
    private final Path srcRoot;
    private final Map<String, CompilationUnit> parsedFiles = new java.util.HashMap<>();

    SourceKeyResolver(Path srcRoot) {
      this.srcRoot = srcRoot;
    }

    String resolveKey(String pptBaseName) {
      int paren = pptBaseName.indexOf('(');
      int closeParen = pptBaseName.lastIndexOf(')');
      if (paren < 0 || closeParen < paren) return null;

      String beforeParen = pptBaseName.substring(0, paren);
      String paramsPart = pptBaseName.substring(paren + 1, closeParen);
      int paramCount =
          paramsPart.isBlank() ? 0 : paramsPart.split(",").length;

      int lastDot = beforeParen.lastIndexOf('.');
      if (lastDot < 0) return null;

      String qualifiedClass = beforeParen.substring(0, lastDot);
      String methodName = beforeParen.substring(lastDot + 1);

      int classDot = qualifiedClass.lastIndexOf('.');
      String pkg = classDot < 0 ? "" : qualifiedClass.substring(0, classDot);
      String simpleClassName =
          classDot < 0 ? qualifiedClass : qualifiedClass.substring(classDot + 1);

      // Daikon reports nested classes as "Outer$Inner" -- there's no "Outer$Inner.java" file,
      // only "Outer.java". Look the file up by the top-level name, but resolve/key by the
      // innermost simple name, matching daikonplusplus's own (JavaProjectScanner) key format,
      // which already drops the outer-class prefix for nested classes.
      int dollarIdx = simpleClassName.indexOf('$');
      String fileClassName = dollarIdx < 0 ? simpleClassName : simpleClassName.substring(0, dollarIdx);
      String keyClassName =
          dollarIdx < 0 ? simpleClassName : simpleClassName.substring(simpleClassName.lastIndexOf('$') + 1);

      Path file = srcRoot.resolve(pkg.replace('.', '/') + "/" + fileClassName + ".java");
      if (!Files.exists(file)) return null;

      CompilationUnit cu =
          parsedFiles.computeIfAbsent(
              file.toString(),
              p -> {
                try {
                  return StaticJavaParser.parse(file);
                } catch (Exception e) {
                  return null;
                }
              });
      if (cu == null) return null;

      java.util.Optional<ClassOrInterfaceDeclaration> maybeClass =
          cu.findFirst(
              ClassOrInterfaceDeclaration.class, c -> c.getNameAsString().equals(keyClassName));
      if (maybeClass.isEmpty()) return null;

      java.util.Optional<MethodDeclaration> maybeMethod =
          maybeClass.get().getMethodsByName(methodName).stream()
              .filter(md -> md.getParameters().size() == paramCount)
              .findFirst();
      if (maybeMethod.isEmpty()) return null;

      MethodDeclaration md = maybeMethod.get();
      String params =
          md.getParameters().stream()
              .map(p -> p.getType().toString())
              .collect(Collectors.joining(","));
      String ret = md.getType().toString();
      String desc = md.getNameAsString() + "(" + params + "):" + ret;

      String prefix = pkg.isEmpty() ? "" : pkg + ".";
      return prefix + keyClassName + "#" + desc;
    }
  }
}
