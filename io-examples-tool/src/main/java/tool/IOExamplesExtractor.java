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
 */
public class IOExamplesExtractor {

  private static final Pattern SAMPLE_HEADER = Pattern.compile("^(.*):::(ENTER|EXIT\\d*)$");

  /** Maximum number of examples retained per method in the output JSON. */
  private static final int MAX_EXAMPLES_PER_METHOD = 20;

  public static void main(String[] args) throws Exception {
    Map<String, String> opts = parseArgs(args);
    String dtracePath = require(opts, "dtrace");
    String srcRoot = require(opts, "src");
    String outPath = require(opts, "out");

    System.out.println("[io-examples-tool] dtrace=" + dtracePath);
    System.out.println("[io-examples-tool] src=" + srcRoot);
    System.out.println("[io-examples-tool] out=" + outPath);

    // pptBaseName (e.g. "com.example.MathUtils.add(int, int)") -> list of {args, return} records
    Map<String, List<Map<String, Object>>> byPpt = new LinkedHashMap<>();

    // nonce -> args recorded at the matching ENTER, awaiting its EXIT
    Map<String, Map<String, String>> pendingEntries = new LinkedHashMap<>();

    int enterCount = 0;
    int pairedCount = 0;

    try (BufferedReader reader = openReader(dtracePath)) {
      List<List<String>> blocks = readBlocks(reader);

      for (List<String> block : blocks) {
        String header = block.get(0);
        if (header.startsWith("ppt ")) continue; // declaration block, not a data sample

        Matcher m = SAMPLE_HEADER.matcher(header);
        if (!m.matches()) continue; // preamble (decl-version, var-comparability, ...)

        String pptBaseName = m.group(1);
        boolean isEnter = m.group(2).equals("ENTER");

        Map<String, String> vars = parseVarTriples(block.subList(1, block.size()));
        String nonce = vars.get("this_invocation_nonce");
        if (nonce == null) continue;

        if (isEnter) {
          enterCount++;
          Map<String, String> entryArgs = new LinkedHashMap<>(vars);
          entryArgs.remove("this_invocation_nonce");
          pendingEntries.put(nonce, entryArgs);
        } else {
          Map<String, String> entryArgs = pendingEntries.remove(nonce);
          if (entryArgs == null) continue; // EXIT with no matching ENTER (truncated trace, etc.)

          List<Map<String, Object>> examples =
              byPpt.computeIfAbsent(pptBaseName, k -> new ArrayList<>());
          if (examples.size() >= MAX_EXAMPLES_PER_METHOD) continue;

          Map<String, Object> record = new LinkedHashMap<>();
          record.put("args", entryArgs);
          record.put("return", vars.get("return"));
          examples.add(record);
          pairedCount++;
        }
      }
    }

    System.out.println("[io-examples-tool] ENTER records=" + enterCount);
    System.out.println("[io-examples-tool] paired ENTER/EXIT examples=" + pairedCount);
    System.out.println("[io-examples-tool] distinct program points=" + byPpt.size());

    SourceKeyResolver resolver = new SourceKeyResolver(Path.of(srcRoot));
    Map<String, List<Map<String, Object>>> index = new LinkedHashMap<>();
    int resolved = 0;
    int unresolved = 0;

    for (Map.Entry<String, List<Map<String, Object>>> e : byPpt.entrySet()) {
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
   * Splits the trace file into blank-line-separated paragraphs ("blocks"), each block being either
   * a declaration (starts with {@code "ppt "}) or a data sample (starts with a program point name
   * ending in {@code :::ENTER} or {@code :::EXIT<n>}).
   */
  private static List<List<String>> readBlocks(BufferedReader reader) throws IOException {
    List<List<String>> blocks = new ArrayList<>();
    List<String> current = new ArrayList<>();

    String line;
    while ((line = reader.readLine()) != null) {
      if (line.isBlank()) {
        if (!current.isEmpty()) {
          blocks.add(current);
          current = new ArrayList<>();
        }
      } else {
        current.add(line);
      }
    }
    if (!current.isEmpty()) blocks.add(current);

    return blocks;
  }

  /** Parses a data sample's body (name/value/mod-bit triples) into a var-name -> value map. */
  private static Map<String, String> parseVarTriples(List<String> lines) {
    Map<String, String> vars = new LinkedHashMap<>();
    for (int i = 0; i + 2 < lines.size(); i += 3) {
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

      Path file = srcRoot.resolve(pkg.replace('.', '/') + "/" + simpleClassName + ".java");
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
              ClassOrInterfaceDeclaration.class, c -> c.getNameAsString().equals(simpleClassName));
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
      return prefix + simpleClassName + "#" + desc;
    }
  }
}

