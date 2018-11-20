package com.vertispan.j2cl;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;

/**
 * Command line launcher for the "dev_mode".
 */
public class DevMode {

    public static void main(String[] args) throws IOException, InterruptedException, ExecutionException {
        Gwt3Options options = new Gwt3OptionsImpl();
        CmdLineParser parser = new CmdLineParser(options);
        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            e.printStackTrace();
            parser.printUsage(System.err);
            System.exit(1);
        }
        ListeningCompiler.run(options);
    }

}
