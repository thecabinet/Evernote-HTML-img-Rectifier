package com.jonathanstafford.evernote;

import com.evernote.edam.error.EDAMErrorCode;
import com.evernote.edam.error.EDAMUserException;
import com.evernote.edam.notestore.NoteStore;
import com.evernote.edam.type.User;
import com.evernote.edam.userstore.AuthenticationResult;
import static com.evernote.edam.userstore.Constants.*;
import com.evernote.edam.userstore.UserStore;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
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
        } catch (EDAMUserException ex) {
            // Note that the error handling here is far more detailed than you would 
            // provide to a real user. It is intended to give you an idea of why the 
            // sample application isn't able to authenticate to our servers.

            // Any time that you contact us about a problem with an Evernote API, 
            // please provide us with the exception parameter and errorcode. 
            String parameter = ex.getParameter();
            EDAMErrorCode errorCode = ex.getErrorCode();

            System.err.println("Authentication failed (parameter: " + parameter + " errorCode: " + errorCode + ")");

            if (errorCode == EDAMErrorCode.INVALID_AUTH) {
                if (parameter.equals("consumerKey")) {
                    if (CONSUMER_KEY.equals("en-edamtest")) {
                        System.err.println("You must replace the variables consumerKey and consumerSecret with the values you received from Evernote.");
                    } else {
                        System.err.println("Your consumer key was not accepted by " + options.getEvernoteHost());
                        System.err.println("This sample client application requires a client API key. If you requested a web service API key, you must authenticate using OAuth as shown in sample/java/oauth");
                    }
                    System.err.println("If you do not have an API Key from Evernote, you can request one from http://www.evernote.com/about/developer/api");
                } else if (parameter.equals("username")) {
                    System.err.println("You must authenticate using a username and password from " + options.getEvernoteHost());
                    if (options.getEvernoteHost().equals("www.evernote.com") == false) {
                        System.err.println("Note that your production Evernote account will not work on " + options.getEvernoteHost() + ",");
                        System.err.println("you must register for a separate test account at https://" + options.getEvernoteHost() + "/Registration.action");
                    }
                } else if (parameter.equals("password")) {
                    System.err.println("The password that you entered is incorrect");
                }
            }

            System.exit(1);
        }

        // The result of a succesful authentication is an opaque authentication token
        // that you will use in all subsequent API calls. If you are developing a
        // web application that authenticates using OAuth, the OAuth access token
        // that you receive would be used as the authToken in subsquent calls.
        String authToken = authResult.getAuthenticationToken();

        // The Evernote NoteStore allows you to accessa user's notes.    
        // In order to access the NoteStore for a given user, you need to know the 
        // logical "shard" that their notes are stored on. The shard ID is included 
        // in the URL used to access the NoteStore.
        User user = authResult.getUser();
        String shardId = user.getShardId();

        System.out.println("Successfully authenticated as " + user.getUsername());

        // Set up the NoteStore client 
        THttpClient noteStoreTrans = new THttpClient(options.getNoteStoreURL(shardId));

        noteStoreTrans.setCustomHeader("User-Agent", HtmlImgRectifier.USER_AGENT);
        TBinaryProtocol noteStoreProt = new TBinaryProtocol(noteStoreTrans);

        NoteStore.Client noteStore = new NoteStore.Client(noteStoreProt, noteStoreProt);

        HtmlImgRectifier rectifier = new HtmlImgRectifier(userStore, authResult, noteStore);
        rectifier.rectify();

    }

    private static class Options {

        @Option(required = false, name = "--help", aliases = {"-h"}, usage = "prints this usage statement")
        public boolean help = false;
        @Option(required = false, name = "--sandbox", aliases = {"-s"}, usage = "uses the Evernote sandbox instance (for development)")
        public boolean sandbox = false;
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
