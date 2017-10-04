package com.vertispan.j2cl;

import com.google.common.base.Preconditions;
import com.google.j2cl.transpiler.J2clTranspiler;
import com.google.j2cl.transpiler.J2clTranspiler.Result;
import com.google.javascript.jscomp.CommandLineRunner;
import com.google.javascript.jscomp.CompilationLevel;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import javax.tools.*;
import javax.tools.JavaCompiler.CompilationTask;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;

import static com.google.common.io.Files.createTempDir;

/**
 * Simple "dev mode" for j2cl+closure, based on the existing bash script. Lots of room for improvement, this
 * isn't intended to be a proposal, just another experiment on the way to one.
 *
 * Things about this I like:
 *   o Treat both jars and jszips as classpaths (ease of dependency system integrations)
 *   o Annotation processors are (or should be) run as an IDE would do, so all kinds of changes are picked up. I
 *     think I got it right to pick up generated classes changes too...
 *
 * Not so good:
 *   o J2CL seems deliberately difficult to integrate (no public, uses threadlocals)
 *   o Haven't yet worked out how to get Closure to notice incremental changes, may be easier to do this by hand.
 *   o Not at all convinced my javac wiring is correct
 *   o Polling for changes
 */
public class DevMode {
    public static class Options {
        @Option(name = "-src", usage = "specify one or more java source directories")
        List<String> sourceDir;
        @Option(name = "-classes", usage = "provide a directory to put compiled bytecode in")
        String classesDir;
        @Option(name = "-jsClasspath", usage = "specify js archive classpath")
        String j2clClasspath;
        @Option(name = "-classpath", usage = "specify java classpath")
        String bytecodeClasspath;
        @Option(name = "-out", usage = "indicates where to write generated JS sources")
        String outputJsPath;

    }
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

        String intermediateJsPath = createTempDir().getPath();
//        System.out.println("intermediate js path " + intermediateJsPath);
        File generatedClassesPath = createTempDir();
//        System.out.println("generated classes path " + generatedClassesPath);


        options.bytecodeClasspath += ":" + options.classesDir;
        List<File> classpath = new ArrayList<>();
        for (String path : options.bytecodeClasspath.split(File.pathSeparator)) {
            System.out.println(path);
            classpath.add(new File(path));
        }

        List<String> javacOptions = Arrays.asList("-implicit:none");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        fileManager.setLocation(StandardLocation.SOURCE_PATH, Collections.emptyList());
        fileManager.setLocation(StandardLocation.SOURCE_OUTPUT, Collections.singleton(generatedClassesPath));
        fileManager.setLocation(StandardLocation.CLASS_PATH, classpath);
        File classesDirFile = new File(options.classesDir);
        classesDirFile.mkdirs();
        Preconditions.checkState(classesDirFile.isDirectory(), "either -classes does not point at a directory, or we couldn't create it");
        fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singleton(classesDirFile));

        //put all j2clClasspath items into a list, we'll copy each time and add generated js
        //TODO put j2cl emul guts in here, jre - or else make them a real dep
        List<String> baseJ2clArgs = Arrays.asList("-cp", options.bytecodeClasspath, "-d", intermediateJsPath);
        List<String> baseClosureArgs = new ArrayList<>(Arrays.asList("--compilation_level", CompilationLevel.BUNDLE.name(), "--js_output_file", options.outputJsPath + "/app.js"));
        for (String zipPath : options.j2clClasspath.split(File.pathSeparator)) {
            Preconditions.checkArgument(new File(zipPath).exists() && new File(zipPath).isFile(), "jszip doesn't exist! %s", zipPath);

            baseClosureArgs.add("--jszip");
            baseClosureArgs.add(zipPath);
        }
        baseClosureArgs.add("--js");
        baseClosureArgs.add(intermediateJsPath + "/**/*.js");//precludes default package
        FileTime lastModified = FileTime.fromMillis(0);
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:**/*.java");
        while (true) {
            // currently polling for changes.
            // block until changes instead? easy to replace with filewatcher, just watch out for java9/osx issues...

            List<String> modifiedJavaFiles = new ArrayList<>();
            FileTime newerThan = lastModified;
            for (String dir : options.sourceDir) {
                Files.find(Paths.get(dir),
                        Integer.MAX_VALUE,
                        (filePath, fileAttr) -> {
                            return !fileAttr.isDirectory()
                                    && fileAttr.lastModifiedTime().compareTo(newerThan) > 0
                                    && matcher.matches(filePath);
                        })
                        .forEach(file -> modifiedJavaFiles.add(file.toString()));
            }
            // don't replace this until the loop finishes successfully, so we know the last time we started a successful compile
            FileTime nextModifiedIfSuccessful = FileTime.fromMillis(System.currentTimeMillis());

            if (modifiedJavaFiles.isEmpty()) {
                Thread.sleep(100);
                continue;
            }
            
            System.out.println(modifiedJavaFiles.size() + " updated java files");
            modifiedJavaFiles.forEach(System.out::println);

            // compile java files with javac into classesDir
            Iterable<? extends JavaFileObject> modifiedFileObjects = fileManager.getJavaFileObjectsFromStrings(modifiedJavaFiles);
            //TODO pass-non null for "classes" to properly kick apt?
            //TODO consider a different classpath for this tasks, so as to not interfere with everything else?

            CompilationTask task = compiler.getTask(null, fileManager, null, javacOptions, null, modifiedFileObjects);
            if (!task.call()) {
                //error occurred, should have been logged, skip the rest of this loop
                continue;
            }

            List<String> j2clArgs = new ArrayList<>(baseJ2clArgs);
            // add all modified Java files
            //TODO don't just use all generated classes, but look for changes maybe?
            j2clArgs.addAll(modifiedJavaFiles);
            Files.find(Paths.get(generatedClassesPath.getAbsolutePath()),
                    Integer.MAX_VALUE,
                    (filePath, fileAttr) ->
                            !fileAttr.isDirectory()
                            && matcher.matches(filePath)
                            /*TODO check modified?*/
                    ).forEach(file -> j2clArgs.add(file.toString()));


            //recompile java->js
//            System.out.println(j2clArgs);

            // Sadly, can't do this, each run of the transpiler MUST be in its own thread, since it
            // can't seem to clean up its threadlocals.
//            Result transpileResult = transpiler.transpile(j2clArgs.toArray(new String[0]));
            ExecutorService executorService = Executors.newSingleThreadExecutor();
            Future<Result> futureResult = executorService.submit(() -> {
                J2clTranspiler transpiler = new J2clTranspiler();
                return transpiler.transpile(j2clArgs.toArray(new String[0]));
            });
            Result transpileResult = futureResult.get();
            transpileResult.getProblems().report(System.out, System.err);
            executorService.shutdownNow();//technically the finalizer will call shutdown, but we can cleanup now
            if (transpileResult.getExitCode() != 0) {
                //print problems
                continue;
            }

            //collect all js into one artifact (currently jscomp, but it would be wonderful to not pay quite so much for this...)
            List<String> jscompArgs = new ArrayList<>(baseClosureArgs);
            System.out.println(jscompArgs);


            CommandLineRunner jscompRunner = new InProcessJsCompRunner(jscompArgs.toArray(new String[0]));
            if (jscompRunner.shouldRunCompiler()) {
                jscompRunner.run();
                if (jscompRunner.hasErrors()) {
                    continue;
                }
            }
            System.out.println("Recommpile of " + modifiedJavaFiles.size() + " source classes finished in " + (System.currentTimeMillis() - nextModifiedIfSuccessful.to(TimeUnit.MILLISECONDS)) + "ms");
            lastModified = nextModifiedIfSuccessful;
        }
    }
    
    static class InProcessJsCompRunner extends CommandLineRunner {
        InProcessJsCompRunner(String[] args) {
            super(args);
            setExitCodeReceiver(ignore -> null);
        }
    }

}
