package com.vertispan.j2cl;

import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.OptionDef;
import org.kohsuke.args4j.spi.DelimitedOptionHandler;
import org.kohsuke.args4j.spi.Setter;

public class FileSeparatorHandler extends DelimitedOptionHandler<String> {
    protected static String sysPathSeperator = System.getProperty("path.separator");
    public FileSeparatorHandler(CmdLineParser parser, OptionDef option, Setter<? super String> setter) {
        super(parser, option, setter, sysPathSeperator, new StringOptionHandler(parser, option, setter));
    }
}
