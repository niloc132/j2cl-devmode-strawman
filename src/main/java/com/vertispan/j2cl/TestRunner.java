package com.vertispan.j2cl;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

public class TestRunner {

    public static void main(String[] args) throws InterruptedException, ExecutionException, IOException {
        Gwt3TestOptions options = new Gwt3TestOptions();
        CmdLineParser parser = new CmdLineParser(options);
        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            e.printStackTrace();
            parser.printUsage(System.err);
            System.exit(1);
        }
        SingleCompiler.run(options.makeOptions());
    }
}
