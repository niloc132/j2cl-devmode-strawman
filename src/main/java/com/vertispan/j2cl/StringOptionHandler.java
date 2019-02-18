package com.vertispan.j2cl;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.OptionDef;
import org.kohsuke.args4j.spi.Messages;
import org.kohsuke.args4j.spi.OneArgumentOptionHandler;
import org.kohsuke.args4j.spi.OptionHandler;
import org.kohsuke.args4j.spi.Setter;

import java.io.File;

/**
 * {@link File} {@link OptionHandler}.
 *
 * @author Kohsuke Kawaguchi
 */
public class StringOptionHandler extends OneArgumentOptionHandler<String> {
    public StringOptionHandler(CmdLineParser parser, OptionDef option, Setter<? super String> setter) {
        super(parser, option, setter);
    }

    @Override
    protected String parse(String argument) throws CmdLineException {
        return argument;
    }

    @Override
    public String getDefaultMetaVariable() {
        return Messages.DEFAULT_META_FILE_OPTION_HANDLER.format();
    }
}

