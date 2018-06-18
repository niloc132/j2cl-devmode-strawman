package com.vertispan.j2cl;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.j2cl.common.Problems;
import com.google.j2cl.frontend.FrontendUtils;
import com.google.j2cl.frontend.FrontendUtils.FileInfo;
import com.google.j2cl.generator.NativeJavaScriptFile;
import com.google.j2cl.tools.gwtincompatible.JavaPreprocessor;
import com.google.j2cl.transpiler.J2clTranspiler;
import com.google.javascript.jscomp.*;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.CompilerOptions.DependencyMode;
import org.apache.commons.codec.digest.DigestUtils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import javax.tools.*;
import javax.tools.JavaCompiler.CompilationTask;
import java.io.*;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static com.google.common.io.Files.createTempDir;

/**
 * Simple "dev mode" for j2cl+closure, based on the existing bash script. Lots of room for improvement, this
 * isn't intended to be a proposal, just another experiment on the way to one.
 *
 * Assumptions:
 *   o The js-compatible JRE is already on the java classpath (need not be on js). Probably not a good one, but
 *     on the other hand, we may want to allow changing out the JRE (or skipping it) in favor of something else.
 *   o A JS entrypoint already exists. Probably safe, should get some APT going soon as discussed, at least to
 *     try it out.
 *
 * Things about this I like:
 *   o Treat both jars and jszips as classpaths (ease of dependency system integrations)
 *   o Annotation processors are (or should be) run as an IDE would do, so all kinds of changes are picked up. I
 *     think I got it right to pick up generated classes changes too...
 *
 * Not so good:
 *   o J2CL seems difficult to integrate (no public, uses threadlocals)
 *   o Not correctly recompiling classes that require it based on dependencies
 *   o Not at all convinced my javac wiring is correct
 *   o Polling for changes
 */
public class DevMode {
    public static class Options {
        @Option(name = "-src", usage = "specify one or more java source directories", required = true)
        List<String> sourceDir = new ArrayList<>();
        @Option(name = "-classpath", usage = "java classpath. bytecode jars are assumed to be pre-" +
                "processed, source jars will be preprocessed, transpiled, and cached. This is only " +
                "done on startup, sources that should be monitored for changes should be passed in " +
                "via -src", required = true)
        String bytecodeClasspath;

        @Option(name = "-jsClasspath", usage = "specify js archive classpath that won't be " +
                "transpiled from sources or classpath. If nothing else, should include " +
                "bootstrap.js.zip and jre.js.zip", required = true)
        String j2clClasspath;

        @Option(name = "-out", usage = "indicates where to write generated JS sources, sourcemaps, " +
                "etc. Should be a directory specific to gwt, anything may be overwritten there, " +
                "but probably should be somewhere your server will pass to the browser", required = true)
        String outputJsPathDir;

        @Option(name = "-classes", usage = "provide a directory to put compiled bytecode in. " +
                "If not specified, a tmp dir will be used. Do not share this directory with " +
                "your IDE or other build tools, unless they also pre-process j2cl sources")
        String classesDir;

        @Option(name = "-entrypoint", aliases = "--entry_point",
                usage = "one or more entrypoints to start the app with, from either java or js", required = true)
        List<String> entrypoint = new ArrayList<>();

        @Option(name = "-jsZipCache", usage = "directory to cache generated jszips in. Should be " +
                "cleared when j2cl version changes", required = true)
        String jsZipCacheDir;

//        @Option(name = "-bytecodeCache", usage = "directory to cache j2cl preprocessed jars in.", required = true)
//        String javaBytecodeCacheDir;

        //lifted straight from closure for consistency
        @Option(name = "--define",
                aliases = {"--D", "-D"},
                usage = "Override the value of a variable annotated @define. "
                        + "The format is <name>[=<val>], where <name> is the name of a @define "
                        + "variable and <val> is a boolean, number, or a single-quoted string "
                        + "that contains no single quotes. If [=<val>] is omitted, "
                        + "the variable is marked true")
        private List<String> define = new ArrayList<>();

        //lifted straight from closure for consistency
        @Option(name = "--externs",
                usage = "The file containing JavaScript externs. You may specify"
                        + " multiple")
        private List<String> externs = new ArrayList<>();

        //lifted straight from closure for consistency
        @Option(
                name = "--compilation_level",
                aliases = {"-O"},
                usage =
                        "Specifies the compilation level to use. Options: "
                                + "BUNDLE, "
                                + "WHITESPACE_ONLY, "
                                + "SIMPLE (default), "
                                + "ADVANCED"
        )
        private String compilationLevel = "BUNDLE";

        //lifted straight from closure for consistency
        @Option(
                name = "--language_out",
                usage =
                        "Sets the language spec to which output should conform. "
                                + "Options: ECMASCRIPT3, ECMASCRIPT5, ECMASCRIPT5_STRICT, "
                                + "ECMASCRIPT6_TYPED (experimental), ECMASCRIPT_2015, ECMASCRIPT_2016, "
                                + "ECMASCRIPT_2017, ECMASCRIPT_NEXT, NO_TRANSPILE"
        )
        private String languageOut = "ECMASCRIPT5";

        //lifted straight from closure for consistency (this should get a rewrite to clarify that for gwt-like
        // behavior, NONE should be avoided. Default changed to strict.
        @Option(
                name = "--dependency_mode",
                usage = "Specifies how the compiler should determine the set and order "
                        + "of files for a compilation. Options: NONE the compiler will include "
                        + "all src files in the order listed, STRICT files will be included and "
                        + "sorted by starting from namespaces or files listed by the "
                        + "--entry_point flag - files will only be included if they are "
                        + "referenced by a goog.require or CommonJS require or ES6 import, LOOSE "
                        + "same as with STRICT but files which do not goog.provide a namespace "
                        + "and are not modules will be automatically added as "
                        + "--entry_point entries. "//Defaults to NONE."
        )
        private CompilerOptions.DependencyMode dependencyMode = CompilerOptions.DependencyMode.STRICT;



        // j2cl-specific flag
        @Option(name = "-declarelegacynamespaces",
                usage =
                        "Enable goog.module.declareLegacyNamespace() for generated goog.module().",
                hidden = true
        )
        protected boolean declareLegacyNamespaces = false;


        private String getIntermediateJsPath() {
            return createDir(outputJsPathDir + "/sources").getPath();
        }

        private File getClassesDir() {
            File classesDirFile;
            if (classesDir != null) {
                classesDirFile = createDir(classesDir);
            } else {
                classesDirFile = createTempDir();
                classesDir = classesDirFile.getAbsolutePath();
            }
            return classesDirFile;
        }

        private static File createDir(String path) {
            File f = new File(path);
            if (f.exists()) {
                Preconditions.checkState(f.isDirectory(), "path already exists but is not a directory " + path);
            } else if (!f.mkdirs()) {
                throw new IllegalStateException("Failed to create directory " + path);
            }
            return f;
        }
    }

    private static PathMatcher javaMatcher = FileSystems.getDefault().getPathMatcher("glob:**/*.java");
    private static PathMatcher nativeJsMatcher = FileSystems.getDefault().getPathMatcher("glob:**/*.native.js");
    private static PathMatcher jsMatcher = FileSystems.getDefault().getPathMatcher("glob:**/*.js");

    public static void main(String[] args) throws IOException, InterruptedException, ExecutionException {

        Options options = new Options();
        CmdLineParser parser = new CmdLineParser(options);
        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            e.printStackTrace();
            parser.printUsage(System.err);
            System.exit(1);
        }

        String intermediateJsPath = options.getIntermediateJsPath();
        System.out.println("intermediate js from j2cl path " + intermediateJsPath);
        File generatedClassesPath = createTempDir();//TODO allow this to be configurable
        System.out.println("generated source path " + generatedClassesPath);
        String sourcesNativeZipPath = File.createTempFile("proj-native", ".zip").getAbsolutePath();

        File classesDirFile = options.getClassesDir();
        System.out.println("output class directory " + classesDirFile);
        options.bytecodeClasspath += ":" + classesDirFile.getAbsolutePath();
        List<File> classpath = new ArrayList<>();
        for (String path : options.bytecodeClasspath.split(File.pathSeparator)) {
            classpath.add(new File(path));
        }

        List<String> javacOptions = Arrays.asList("-implicit:none");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        fileManager.setLocation(StandardLocation.SOURCE_PATH, Collections.emptyList());
        fileManager.setLocation(StandardLocation.SOURCE_OUTPUT, Collections.singleton(generatedClassesPath));
        fileManager.setLocation(StandardLocation.CLASS_PATH, classpath);
        fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singleton(classesDirFile));

        // put all j2clClasspath items into a list, we'll copy each time and add generated js
        List<String> baseJ2clArgs = new ArrayList<>(Arrays.asList("-cp", options.bytecodeClasspath, "-d", intermediateJsPath));
        if (options.declareLegacyNamespaces) {
            baseJ2clArgs.add("-declarelegacynamespaces");
        }

        String intermediateJsOutput = options.outputJsPathDir + "/app.js";
        CompilationLevel compilationLevel = CompilationLevel.fromString(options.compilationLevel);
        List<String> baseClosureArgs = new ArrayList<>(Arrays.asList(
                "--compilation_level", compilationLevel.name(),
                "--js_output_file", intermediateJsOutput,// temp file to write to before we insert the missing line at the top
                "--dependency_mode", options.dependencyMode.name(),// force STRICT mode so that the compiler at least orders the inputs
                "--language_out", options.languageOut
        ));
        if (compilationLevel == CompilationLevel.BUNDLE) {
            // support BUNDLE mode, with no remote fetching for dependencies)
            baseClosureArgs.add("--define");
            baseClosureArgs.add( "goog.ENABLE_DEBUG_LOADER=false");
        }

        for (String define : options.define) {
            baseClosureArgs.add("--define");
            baseClosureArgs.add(define);
        }
        for (String entrypoint : options.entrypoint) {
            baseClosureArgs.add("--entry_point");
            baseClosureArgs.add(entrypoint);
        }
        for (String extern : options.externs) {
            baseClosureArgs.add("--externs");
            baseClosureArgs.add(extern);
        }

        // configure a persistent input store - we'll reuse this and not the compiler for now, to cache the ASTs,
        // and still allow jscomp to be in modes other than BUNDLE
        PersistentInputStore persistentInputStore = new PersistentInputStore();

        for (String zipPath : options.j2clClasspath.split(File.pathSeparator)) {
            Preconditions.checkArgument(new File(zipPath).exists() && new File(zipPath).isFile(), "jszip doesn't exist! %s", zipPath);

            baseClosureArgs.add("--jszip");
            baseClosureArgs.add(zipPath);

            // add JS zip file to the input store - no nice digest, since so far we don't support changes to the zip
            persistentInputStore.addInput(zipPath, "0");
        }
        baseClosureArgs.add("--js");
        baseClosureArgs.add(intermediateJsPath + "/**/*.js");//precludes default package

        //pre-transpile all dependency sources to our cache dir, add those cached items to closure args
        List<String> transpiledDependencies = handleDependencies(options, classpath, baseJ2clArgs, persistentInputStore);
        baseClosureArgs.addAll(transpiledDependencies);


        FileTime lastModified = FileTime.fromMillis(0);
        FileTime lastSuccess = FileTime.fromMillis(0);

        while (true) {
            // currently polling for changes.
            // block until changes instead? easy to replace with filewatcher, just watch out for java9/osx issues...

            List<FileInfo> modifiedJavaFiles = new ArrayList<>();
            FileTime newerThan = lastModified;
            long pollStarted = System.currentTimeMillis();

            //this isn't quite right - should check for _at least one_ newer than lastModified, and if so, recompile all
            //newer than lastSuccess
            //also, should look for .native.js too, but not collect them
            for (String dir : options.sourceDir) {
                Files.find(Paths.get(dir),
                        Integer.MAX_VALUE,
                        (filePath, fileAttr) -> {
                            return !fileAttr.isDirectory()
                                    && fileAttr.lastModifiedTime().compareTo(newerThan) > 0
                                    && javaMatcher.matches(filePath);
                        })
                        .forEach(file -> modifiedJavaFiles.add(FileInfo.create(file.toString(), file.toString())));
            }
            long pollTime = System.currentTimeMillis() - pollStarted;
            // don't replace this until the loop finishes successfully, so we know the last time we started a successful compile
            FileTime nextModifiedIfSuccessful = FileTime.fromMillis(System.currentTimeMillis());

            if (modifiedJavaFiles.isEmpty()) {
                Thread.sleep(100);
                continue;
            }

            //collect native files in zip, but only if that file is also present in the changed .java sources
            boolean anyNativeJs = false;
            try (ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(sourcesNativeZipPath))) {
                for (String dir : options.sourceDir) {
                    anyNativeJs |= Files.find(Paths.get(dir), Integer.MAX_VALUE, (path, attrs) -> shouldZip(path, modifiedJavaFiles)).reduce(false, (ignore, file) -> {
                        try {
                            zipOutputStream.putNextEntry(new ZipEntry(Paths.get(dir).toAbsolutePath().relativize(file.toAbsolutePath()).toString()));
                            zipOutputStream.write(Files.readAllBytes(file));
                            zipOutputStream.closeEntry();
                            return true;
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }, (a, b) -> a || b);
                }
            }

            // blindly copy any JS in sources that aren't a native.js
            // TODO be less "blind" about this, only copy changed files?
            for (String dir : options.sourceDir) {
                Files.find(Paths.get(dir), Integer.MAX_VALUE, (path, attrs) -> jsMatcher.matches(path) && !nativeJsMatcher.matches(path))
                        .forEach(path -> {
                            try {
                                Files.copy(path, Paths.get(Paths.get(dir).toAbsolutePath().relativize(path.toAbsolutePath()).toString()));
                            } catch (IOException e) {
                                throw new RuntimeException("failed to copy plain js", e);
                            }
                        });
            }

            System.out.println(modifiedJavaFiles.size() + " updated java files");
//            modifiedJavaFiles.forEach(System.out::println);

            // compile java files with javac into classesDir
            Iterable<? extends JavaFileObject> modifiedFileObjects = fileManager.getJavaFileObjectsFromStrings(modifiedJavaFiles.stream().map(FileInfo::sourcePath).collect(Collectors.toList()));
            //TODO pass-non null for "classes" to properly kick apt?
            //TODO consider a different classpath for this tasks, so as to not interfere with everything else?

            long javacStarted = System.currentTimeMillis();
            CompilationTask task = compiler.getTask(null, fileManager, null, javacOptions, null, modifiedFileObjects);
            if (!task.call()) {
                //error occurred, should have been logged, skip the rest of this loop
                continue;
            }
            long javacTime = System.currentTimeMillis() - javacStarted;

            // add all modified Java files
            //TODO don't just use all generated classes, but look for changes maybe?

            Files.find(Paths.get(generatedClassesPath.getAbsolutePath()),
                    Integer.MAX_VALUE,
                    (filePath, fileAttr) ->
                            !fileAttr.isDirectory()
                            && javaMatcher.matches(filePath)
                            /*TODO check modified?*/
                    ).forEach(file -> modifiedJavaFiles.add(FileInfo.create(file.toString(), file.toString())));

            // run preprocessor on changed files
            File processed = File.createTempFile("preprocessed", ".srcjar");
            try (FileSystem out = FrontendUtils.initZipOutput(processed.getAbsolutePath(), new Problems())) {
                JavaPreprocessor.preprocessFiles(modifiedJavaFiles, out, new Problems());
            }

            List<String> j2clArgs = new ArrayList<>(baseJ2clArgs);
            if (anyNativeJs) {
                j2clArgs.add("-nativesourcepath");
                j2clArgs.add(sourcesNativeZipPath);
            }
            j2clArgs.add(processed.getAbsolutePath());

            long j2clStarted = System.currentTimeMillis();
            Problems transpileResult = transpile(j2clArgs);

            processed.delete();

            if (transpileResult.reportAndGetExitCode(System.err) != 0) {
                //print problems
                continue;
            }
            long j2clTime = System.currentTimeMillis() - j2clStarted;

            // TODO copy the generated .js files, so that we only feed the updated ones the jscomp, stop messing around with args...
            long jscompStarted = System.currentTimeMillis();
            if (!jscomp(baseClosureArgs, persistentInputStore, intermediateJsPath)) {
                continue;
            }
            long jscompTime = System.currentTimeMillis() - jscompStarted;

            System.out.println("Recompile of " + modifiedJavaFiles.size() + " source classes finished in " + (System.currentTimeMillis() - nextModifiedIfSuccessful.to(TimeUnit.MILLISECONDS)) + "ms");
            System.out.println("poll: " + pollTime + "millis");
            System.out.println("javac: " + javacTime + "millis");
            System.out.println("j2cl: " + j2clTime + "millis");
            System.out.println("jscomp: " + jscompTime + "millis");
            lastModified = nextModifiedIfSuccessful;
        }
    }

    private static List<String> handleDependencies(Options options, List<File> classpath, List<String> baseJ2clArgs, PersistentInputStore persistentInputStore) throws IOException, InterruptedException, ExecutionException {
        List<String> additionalClosureArgs = new ArrayList<>();
        for (File file : classpath) {
            if (!file.exists()) {
                throw new IllegalStateException(file + " does not exist!");
            }
            //TODO maybe skip certain files that have already been transpiled
            if (file.isDirectory()) {
                continue;//...hacky, but probably just classes dir
            }

            // hash the file, see if we already have one
            String hash = hash(file);
            String jszipOut = options.jsZipCacheDir + "/" + hash + "-" + file.getName() + ".js.zip";
            File jszipOutFile = new File(jszipOut);
            if (jszipOutFile.exists()) {
                additionalClosureArgs.add("--jszip");
                additionalClosureArgs.add(jszipOut);

                persistentInputStore.addInput(jszipOut, "0");
                continue;//already exists, we'll use it
            }

            // run preprocessor
            File processed = File.createTempFile("preprocessed", ".srcjar");
            try (FileSystem out = FrontendUtils.initZipOutput(processed.getAbsolutePath(), new Problems())) {
                ImmutableList<FileInfo> allSources = FrontendUtils.getAllSources(Collections.singletonList(file.getAbsolutePath()), new Problems()).collect(ImmutableList.toImmutableList());
                if (allSources.isEmpty()) {
                    System.out.println("no sources in file " + file);
                    continue;
                }
                JavaPreprocessor.preprocessFiles(allSources, out, new Problems());
            }

            //TODO javac these first, so we have consistent bytecode, and use that to rebuild the classpath

            List<String> pretranspile = new ArrayList<>(baseJ2clArgs);
            pretranspile.addAll(Arrays.asList("-cp", options.bytecodeClasspath, "-d", jszipOut, "-nativesourcepath", file.getAbsolutePath(), processed.getAbsolutePath()));
            Problems result = transpile(pretranspile);

            // blindly copy any JS in sources that aren't a native.js
            ZipFile zipInputFile = new ZipFile(file);

            processed.delete();
            if (result.reportAndGetExitCode(System.err) == 0) {
                try (FileSystem fs = FileSystems.newFileSystem(URI.create("jar:" + jszipOutFile.toURI()), Collections.singletonMap("create", "true"))) {
                    for (ZipEntry entry : Collections.list(zipInputFile.entries())) {
                        Path entryPath = Paths.get(entry.getName());
                        if (jsMatcher.matches(entryPath) && !nativeJsMatcher.matches(entryPath)) {
                            try (InputStream inputStream = zipInputFile.getInputStream(entry)) {
                                Path path = fs.getPath(entry.getName()).toAbsolutePath();
                                Files.createDirectories(path.getParent());
                                // using StandardCopyOption.REPLACE_EXISTING seems overly pessimistic, but i can't get it to work without it
                                Files.copy(inputStream, path, StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                    }
                }

                additionalClosureArgs.add("--jszip");
                additionalClosureArgs.add(jszipOut);

                persistentInputStore.addInput(jszipOut, "0");
            } else {
                jszipOutFile.delete();
                // ignoring failure for now, TODO don't!
                // This is actually slightly tricky - we can't cache failure, since the user might stop and fix the classpath
                // and then the next build will work, but on the other hand we don't want to fail building jsinterop-base
                // over and over again either.
                System.out.println("Failed compiling " + file + " to " + jszipOutFile.getName() + ", optionally copy a manual version to the cache to avoid this error");
            }
        }
        return additionalClosureArgs;
    }

    private static String hash(File file) {
        try (FileInputStream stream = new FileInputStream(file)) {
            return DigestUtils.md5Hex(stream);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Transpiles Java to Js. Should have the same effect as running the main directly, except by running
     * it here we don't System.exit at the end, so the JVM can stay hot.
     */
    private static Problems transpile(List<String> j2clArgs) {
        return J2clTranspiler.transpile(j2clArgs.toArray(new String[0]));
    }

    private static boolean shouldZip(Path path, List<FileInfo> modifiedJavaFiles) {
        return nativeJsMatcher.matches(path);// && matchesChangedJavaFile(path, modifiedJavaFiles);
    }

    private static boolean matchesChangedJavaFile(Path path, List<String> modifiedJavaFiles) {
        String pathString = path.toString();
        String nativeFilePath = pathString.substring(0, pathString.lastIndexOf(NativeJavaScriptFile.NATIVE_EXTENSION));
        return modifiedJavaFiles.stream().anyMatch(javaPath -> javaPath.startsWith(nativeFilePath));
    }

    private static boolean jscomp(List<String> baseClosureArgs, PersistentInputStore persistentInputStore, String updatedJsDirectories) throws IOException {
        // collect all js into one artifact (currently jscomp, but it would be wonderful to not pay quite so much for this...)
        List<String> jscompArgs = new ArrayList<>(baseClosureArgs);

        // Build a new compiler for this run, but share the cached js ASTs
        Compiler jsCompiler = new Compiler(System.err);
        jsCompiler.setPersistentInputStore(persistentInputStore);

        // sanity check args
        CommandLineRunner jscompRunner = new InProcessJsCompRunner(jscompArgs.toArray(new String[0]), jsCompiler);
        if (!jscompRunner.shouldRunCompiler()) {
            return false;
        }

        // for each file in the updated dir
        long timestamp = System.currentTimeMillis();
        Files.find(Paths.get(updatedJsDirectories), Integer.MAX_VALUE, (path, attrs) -> jsMatcher.matches(path)).forEach((Path path) -> {
            // add updated JS file to the input store with timestamp instead of digest for now
            persistentInputStore.addInput(path.toString(), timestamp + "");
        });
        //TODO how do we handle deleted files? If they are truly deleted, nothing should reference them, and the module resolution should shake them out, at only the cost of a little memory?

        jscompRunner.run();

        if (jscompRunner.hasErrors()) {
            return false;
        }
        if (jsCompiler.getModules() != null) {
            // clear out the compiler input for the next goaround
            jsCompiler.resetCompilerInput();
        }
        return true;
    }

    static class InProcessJsCompRunner extends CommandLineRunner {
        private final Compiler compiler;
        InProcessJsCompRunner(String[] args, Compiler compiler) {
            super(args);
            this.compiler = compiler;
            setExitCodeReceiver(ignore -> null);
        }

        @Override
        protected Compiler createCompiler() {
            return compiler;
        }
    }

}
