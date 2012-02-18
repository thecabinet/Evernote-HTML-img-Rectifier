package com.jonathanstafford.evernote;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.OptionDef;
import org.kohsuke.args4j.spi.OneArgumentOptionHandler;
import org.kohsuke.args4j.spi.Setter;

public class DateOptionHandler extends OneArgumentOptionHandler<Date> {

    private final DateFormat dateFormat;

    public DateOptionHandler(CmdLineParser parser, OptionDef option, Setter<? super Date> setter) {
        super(parser, option, setter);

        dateFormat = new SimpleDateFormat("yyyy-MM-dd");
    }

    @Override
    protected Date parse(String argument) throws CmdLineException {
        try {
            return dateFormat.parse(argument);
        } catch (ParseException e) {
            throw new CmdLineException(owner, "could not parse '" + argument + "' as 'YYYY-MM-DD'", e);
        }
    }
}
