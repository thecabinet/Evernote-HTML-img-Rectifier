package com.jonathanstafford.evernote;

import com.evernote.edam.error.EDAMErrorCode;
import com.evernote.edam.error.EDAMUserException;
import com.evernote.edam.notestore.NoteStore;
import com.evernote.edam.type.Notebook;
import com.evernote.edam.type.User;
import com.evernote.edam.userstore.AuthenticationResult;
import static com.evernote.edam.userstore.Constants.*;
import com.evernote.edam.userstore.UserStore;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.THttpClient;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

public class Driver {

    private static final String CONSUMER_KEY;
    private static final String CONSUMER_SECRET;

    static {
        InputStream inputStream = ClassLoader.getSystemResourceAsStream("evernote-html-img-rectifier.properties");
        Properties properties = new Properties();
        try {
            properties.load(inputStream);
        } catch (IOException e) {
            throw new IOError(new IOException("couldn't load properties from evernote-html-img-rectifier.properties", e));
        }

        CONSUMER_KEY = properties.getProperty("evernote.consumer.key");
        CONSUMER_SECRET = properties.getProperty("evernote.consumer.secret");
    }

    public static void main(String[] args) throws Exception {
        Options options = new Options();
        CmdLineParser parser = new CmdLineParser(options);
        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            System.err.println();
            System.err.println("usage: java -jar evernote-html-img-rectifier.jar [--options] <username> <password>");
            parser.printUsage(System.err);
            System.exit(1);
        }

        if (options.help) {
            System.out.println("usage: java -jar evernote-html-img-rectifier.jar [--options] <username> <password>");
            parser.printUsage(System.out);
            System.exit(0);
        }

        THttpClient userStoreTrans = new THttpClient(options.getUserStoreURL());
        userStoreTrans.setCustomHeader("User-Agent", HtmlImgRectifier.USER_AGENT);
        TBinaryProtocol userStoreProt = new TBinaryProtocol(userStoreTrans);
        UserStore.Client userStore = new UserStore.Client(userStoreProt, userStoreProt);

        boolean versionOk = userStore.checkVersion(HtmlImgRectifier.NAME,
                EDAM_VERSION_MAJOR, EDAM_VERSION_MINOR);
        if (!versionOk) {
            throw new IllegalArgumentException("expected EverNote EDAM version " + EDAM_VERSION_MAJOR + "." + EDAM_VERSION_MINOR);
        }

        AuthenticationResult authResult = null;
        try {
            String username = options.username;
            String password = options.password;
            authResult = userStore.authenticate(username, password, CONSUMER_KEY, CONSUMER_SECRET);
        } catch (EDAMUserException e) {
            String parameter = e.getParameter();
            EDAMErrorCode errorCode = e.getErrorCode();

            System.err.println("Authentication failed (parameter: " + parameter + " errorCode: " + errorCode + ")");

            if (errorCode == EDAMErrorCode.INVALID_AUTH) {
                if (parameter.equals("consumerKey")) {
                    System.err.println("the api key was not accepted!  is it activated for " + options.getEvernoteHost() + "?");
                } else if (parameter.equals("username")) {
                    System.err.println("'" + options.username + "' is not a valid username");
                } else if (parameter.equals("password")) {
                    System.err.println("the password for " + options.username + " was invalid");
                }
            }

            System.exit(1);
        }

        User user = authResult.getUser();
        String shardId = user.getShardId();

        THttpClient noteStoreTrans = new THttpClient(options.getNoteStoreURL(shardId));
        noteStoreTrans.setCustomHeader("User-Agent", HtmlImgRectifier.USER_AGENT);
        TBinaryProtocol noteStoreProt = new TBinaryProtocol(noteStoreTrans);
        NoteStore.Client noteStore = new NoteStore.Client(noteStoreProt, noteStoreProt);

        HtmlImgRectifier rectifier = new HtmlImgRectifier(userStore, authResult, noteStore);

        if (options.ctime != null) {
            rectifier.setCreatedTime(options.ctime);
        }

        if (options.mtime != null) {
            rectifier.setUpdatedTime(options.mtime);
        }

        if (options.notebook != null) {
            List<Notebook> notebooks = noteStore.listNotebooks(authResult.getAuthenticationToken());
            for (Notebook notebook : notebooks) {
                if (notebook.getName().equals(options.notebook)) {
                    rectifier.rectify(notebook);
                    System.exit(0);
                }
            }

            System.err.println("couldn't find a Notebook named '" + options.notebook + "'");
            System.exit(2);
        } else {
            rectifier.rectify();
        }
    }

    private static class Options {

        @Option(required = false, name = "--help", aliases = {"-h"}, usage = "prints this usage statement")
        public boolean help = false;
        @Option(required = false, name = "--sandbox", aliases = {"-s"}, usage = "uses the Evernote sandbox instance (for development)")
        public boolean sandbox = false;
        @Option(required = false, name = "--notebook", aliases = {"-n"}, usage = "only processes notes in the notebook with the specified name")
        public String notebook;
        @Option(required = false, name = "--created", aliases = {"-c"}, usage = "only processes notes created since the specified time", handler = DateOptionHandler.class)
        public Date ctime;
        @Option(required = false, name = "--updated", aliases = {"-u"}, usage = "only processes notes updated since the specified time", handler = DateOptionHandler.class)
        public Date mtime;
        @Argument(required = true, index = 0, metaVar = "username", usage = "username")
        public String username;
        @Argument(required = true, index = 1, metaVar = "password", usage = "password")
        public String password;

        public String getEvernoteHost() {
            if (sandbox) {
                return "sandbox.evernote.com";
            } else {
                return "www.evernote.com";
            }
        }

        public String getUserStoreURL() {
            return "https://" + getEvernoteHost() + "/edam/user";
        }

        public String getNoteStoreURL(String shardId) {
            return "https://" + getEvernoteHost() + "/edam/note/" + shardId;
        }
    }
}
