# daikon-trace-tools

Standalone command-line tools used by the `promptstudy` HPC pipeline to turn
Daikon Chicory `.dtrace` trace files and compiled project classes into JSON
context indexes consumed by `daikonplusplus`'s `IO_EXAMPLES` and `CALL_SITE`
context kinds.

## io-examples-tool

`io-examples-tool/` — recovered in full from an earlier Claude session's
transcript after the compiled jar (`io-examples-tool.jar`) turned out to be
the only surviving artifact on the HPC cluster; the original source
directory itself no longer existed on disk. Recovery was exact (the tool's
own startup log lines match byte-for-byte against real job output), but no
`pom.xml` survived alongside it — the one in this repo was written fresh to
match the source's imports (Jackson databind, JavaParser core) and how the
jar is actually invoked on the cluster.

Build:
```
cd io-examples-tool
mvn -q package
# -> target/io-examples-tool-1.0.0.jar (shaded, main class tool.IOExamplesExtractor)
```

Usage (matches the cluster submit scripts):
```
java -Xmx<N>g -jar io-examples-tool.jar \
  --dtrace <path/to/project.dtrace> \
  --src <path/to/src/main/java> \
  --out <path/to/project_io_examples.json>
```

### Known bug — not yet fixed here

`IOExamplesExtractor.readBlocks()` reads the dtrace file line-by-line via
`BufferedReader.readLine()`. Daikon's dtrace format writes array-valued
variables element-by-element **inline on one line**, so a method with a
large array argument/field produces one pathologically long line. On
libgdx's `com.badlogic.gdx.(utils|math)` trace this hit an actual
`OutOfMemoryError` inside `readLine()` even with a 260GB heap — a single
line was too large to materialize as one `String`, regardless of heap size.

This needs a real fix (stream/bound individual line reads rather than
materializing them whole) before `io-examples-tool` can be trusted against
array-heavy code. Not yet done.

## callsite-tool

**Source not recoverable.** No session transcript accessible to Claude
contains this tool ever being written — only the compiled
`callsite-tool.jar` exists on the cluster. It was very likely built in an
earlier session with no retained logs. It appears to be WALA-based (its
runtime log output includes WALA-style call-graph node lines like `got NEW
<Primordial,...>`) and is invoked as:
```
java -Xmx<N>g -jar callsite-tool.jar \
  --classes <path/to/target/classes> \
  --classpath <colon-separated dependency jars> \
  --src <path/to/src/main/java> \
  --out <path/to/project_callsites.json>
```
producing a JSON object keyed by `pkg.Class#method(paramTypes):returnType`,
mapping to a list of `{callerKey, callerJavadoc, callSite}` records. If this
tool ever needs modification, it will have to be rewritten from scratch
against this known CLI contract and output schema — not patched, since
there's nothing to patch.
