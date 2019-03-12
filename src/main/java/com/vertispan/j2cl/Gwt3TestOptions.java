package com.vertispan.j2cl;

import org.kohsuke.args4j.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class Gwt3TestOptions {

    @Option(name = "-test", required = true)
    List<String> testClasses = new ArrayList<>();

    @Option(name = "-src", usage = "specify one or more java source directories", required = true)
    List<String> sourceDir = new ArrayList<>();
    @Option(name = "-classpath", usage = "java classpath. bytecode jars are assumed to be pre-" +
            "processed, source jars will be preprocessed, transpiled, and cached. This is only " +
            "done on startup, sources that should be monitored for changes should be passed in " +
            "via -src", required = true, handler = FileSeparatorHandler.class)
    List<String> bytecodeClasspath;

    @Option(name = "-jsClasspath", usage = "specify js archive classpath that won't be " +
            "transpiled from sources or classpath. If nothing else, should include " +
            "bootstrap.js.zip and jre.js.zip", required = true, handler = FileSeparatorHandler.class)
    List<String> j2clClasspath;

    @Option(name = "-javacBootClasspath", usage = "Path to the javac-bootstrap-classpath jar, " +
            "so javac can correctly compile java sources", required = true)
    String javacBootClasspath;

    @Option(name = "-out", usage = "indicates where to write generated JS sources, sourcemaps, " +
            "etc. Should be a directory specific to gwt, anything may be overwritten there, " +
            "but probably should be somewhere your server will pass to the browser", required = true)
    String outputJsPathDir;

    @Option(name = "-classes", usage = "provide a directory to put compiled bytecode in. " +
            "If not specified, a tmp dir will be used. Do not share this directory with " +
            "your IDE or other build tools, unless they also pre-process j2cl sources")
    String classesDir;


    @Option(name = "-jsZipCache", usage = "directory to cache generated jszips in. Should be " +
            "cleared when j2cl version changes", required = true)
    String jsZipCacheDir;

    //lifted straight from closure for consistency
    @Option(name = "--define",
            aliases = {"--D", "-D"},
            usage = "Override the value of a variable annotated @define. "
                    + "The format is <name>[=<val>], where <name> is the name of a @define "
                    + "variable and <val> is a boolean, number, or a single-quoted string "
                    + "that contains no single quotes. If [=<val>] is omitted, "
                    + "the variable is marked true")
    List<String> define = new ArrayList<>();

    //lifted straight from closure for consistency
    @Option(name = "--externs",
            usage = "The file containing JavaScript externs. You may specify"
                    + " multiple")
    List<String> externs = new ArrayList<>();


    // j2cl-specific flag
    @Option(name = "-declarelegacynamespaces",
            usage =
                    "Enable goog.module.declareLegacyNamespace() for generated goog.module().",
            hidden = true
    )
    boolean declareLegacyNamespaces = false;

    public Gwt3Options makeOptions() {
        return new Gwt3OptionsImplBuilder()
                .setSourceDir(sourceDir)
                .setBytecodeClasspath(bytecodeClasspath)
                .setJ2clClasspath(j2clClasspath)
                .setJavacBootClasspath(javacBootClasspath)
                .setOutputJsPathDir(outputJsPathDir)
                .setClassesDir(classesDir)
                .setClassesDir(classesDir)
                .setJsZipCacheDir(jsZipCacheDir)
                .setDefine(define)
                .setExterns(externs)
                .setDeclareLegacyNamespaces(declareLegacyNamespaces)

                .setEntrypoint(makeEntrypointNames())

                .createGwt3OptionsImpl();
    }

    private List<String> makeEntrypointNames() {
        // to make a test, first we assume the annotation processors have run correctly, and then
        // we mangle names
        return testClasses.stream().map(c -> "javatests." + c + "_AdapterSuite").collect(Collectors.toList());
    }
}
