package com.vertispan.j2cl;

import com.google.javascript.jscomp.CompilerOptions;

import java.util.ArrayList;
import java.util.List;

public class Gwt3OptionsImplBuilder {
    private List<String> sourceDir = new ArrayList<>();
    private List<String> bytecodeClasspath;
    private List<String> j2clClasspath = new ArrayList<>();
    private String javacBootClasspath;
    private String outputJsPathDir;
    private String classesDir;
    private List<String> entrypoint = new ArrayList<>();
    private String jsZipCacheDir;
    private List<String> define = new ArrayList<>();
    private List<String> externs = new ArrayList<>();
    private String compilationLevel = "BUNDLE";
    private String languageOut = "ECMASCRIPT5";
    private CompilerOptions.DependencyMode dependencyMode = CompilerOptions.DependencyMode.STRICT;
    private boolean declareLegacyNamespaces = false;

    public Gwt3OptionsImplBuilder setSourceDir(List<String> sourceDir) {
        this.sourceDir = sourceDir;
        return this;
    }

    public Gwt3OptionsImplBuilder setBytecodeClasspath(List<String> bytecodeClasspath) {
        this.bytecodeClasspath = bytecodeClasspath;
        return this;
    }

    public Gwt3OptionsImplBuilder setJ2clClasspath(List<String> j2clClasspath) {
        this.j2clClasspath = j2clClasspath;
        return this;
    }

    public Gwt3OptionsImplBuilder setJavacBootClasspath(String javacBootClasspath) {
        this.javacBootClasspath = javacBootClasspath;
        return this;
    }

    public Gwt3OptionsImplBuilder setOutputJsPathDir(String outputJsPathDir) {
        this.outputJsPathDir = outputJsPathDir;
        return this;
    }

    public Gwt3OptionsImplBuilder setClassesDir(String classesDir) {
        this.classesDir = classesDir;
        return this;
    }

    public Gwt3OptionsImplBuilder setEntrypoint(List<String> entrypoint) {
        this.entrypoint = entrypoint;
        return this;
    }

    public Gwt3OptionsImplBuilder setJsZipCacheDir(String jsZipCacheDir) {
        this.jsZipCacheDir = jsZipCacheDir;
        return this;
    }

    public Gwt3OptionsImplBuilder setDefine(List<String> define) {
        this.define = define;
        return this;
    }

    public Gwt3OptionsImplBuilder setExterns(List<String> externs) {
        this.externs = externs;
        return this;
    }

    public Gwt3OptionsImplBuilder setCompilationLevel(String compilationLevel) {
        this.compilationLevel = compilationLevel;
        return this;
    }

    public Gwt3OptionsImplBuilder setLanguageOut(String languageOut) {
        this.languageOut = languageOut;
        return this;
    }

    public Gwt3OptionsImplBuilder setDependencyMode(CompilerOptions.DependencyMode dependencyMode) {
        this.dependencyMode = dependencyMode;
        return this;
    }

    public Gwt3OptionsImplBuilder setDeclareLegacyNamespaces(boolean declareLegacyNamespaces) {
        this.declareLegacyNamespaces = declareLegacyNamespaces;
        return this;
    }

    public Gwt3OptionsImpl createGwt3OptionsImpl() {
        return new Gwt3OptionsImpl(sourceDir, bytecodeClasspath, j2clClasspath, javacBootClasspath, outputJsPathDir, classesDir, entrypoint, jsZipCacheDir, define, externs, compilationLevel, languageOut, dependencyMode, declareLegacyNamespaces);
    }
}