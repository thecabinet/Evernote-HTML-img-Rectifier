package com.jonathanstafford.evernote;

import com.evernote.edam.error.EDAMSystemException;
import com.evernote.edam.error.EDAMUserException;
import com.evernote.edam.notestore.NoteFilter;
import com.evernote.edam.notestore.NoteList;
import com.evernote.edam.notestore.NoteStore;
import com.evernote.edam.type.*;
import com.evernote.edam.userstore.AuthenticationResult;
import com.evernote.edam.userstore.Constants;
import com.evernote.edam.userstore.UserStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.*;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class HtmlImgRectifier {

    public static final String NAME = "HTML img Rectifier";
    public static final String VERSION = "0.1";
    public static final String USER_AGENT = NAME + "/" + VERSION;
    private static final Logger LOGGER = LoggerFactory.getLogger(HtmlImgRectifier.class);
    private final UserStore.Client userStore;
    private AuthenticationResult authResult;
    private long authResultExpirationTime;
    private final NoteStore.Client noteStore;
    private final DocumentBuilder documentBuilder;
    private final Transformer transformer;
    private final HttpClient httpClient;
    private final MessageDigest md5;

    /**
     * Creates an <tt>HtmlImgRectifier</tt>.
     *
     * @param userStore the <tt>UserStore.Client</tt> of the account to be
     * manipulated; used to renew the <tt>AuthenticationResult</tt>, if
     * necessary.
     * @param authResult a freshly created <tt>AuthenticationResult</tt> of the
     * to be manipulated.
     * @param noteStore the <tt>NoteStore.Client</tt> of the account to be
     * manipulated.
     * @throws Exception
     */
    public HtmlImgRectifier(UserStore.Client userStore, AuthenticationResult authResult, NoteStore.Client noteStore) throws Exception {
        this.userStore = userStore;
        this.authResult = authResult;
        authResultExpirationTime = System.currentTimeMillis() + authResult.getExpiration() - authResult.getCurrentTime();
        this.noteStore = noteStore;

        // doublecheck that we can talk to the server, since the user might pass
        // us a different version of the Clients than we're compiled with
        boolean versionOk = userStore.checkVersion(USER_AGENT,
                com.evernote.edam.userstore.Constants.EDAM_VERSION_MAJOR,
                com.evernote.edam.userstore.Constants.EDAM_VERSION_MINOR);
        if (!versionOk) {
            throw new IllegalArgumentException("expected EverNote EDAM version " + Constants.EDAM_VERSION_MAJOR + "." + Constants.EDAM_VERSION_MINOR);
        }

        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        documentBuilderFactory.setCoalescing(false);
        documentBuilderFactory.setExpandEntityReferences(false);
        documentBuilderFactory.setIgnoringComments(false);
        documentBuilderFactory.setIgnoringElementContentWhitespace(false);
        documentBuilderFactory.setNamespaceAware(false);
        documentBuilderFactory.setValidating(false);
        documentBuilderFactory.setXIncludeAware(false);
        documentBuilder = documentBuilderFactory.newDocumentBuilder();
        documentBuilder.setEntityResolver(new EntityResolver() {

            public InputSource resolveEntity(String publicId, String systemId) throws SAXException, IOException {
                if (systemId.equals("http://xml.evernote.com/pub/enml2.dtd")) {
                    return new InputSource(new StringReader(""));
                } else {
                    return null;
                }
            }
        });

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, "http://xml.evernote.com/pub/enml2.dtd");
        transformer.setOutputProperty(OutputKeys.STANDALONE, "no");

        httpClient = new HttpClient();
        md5 = MessageDigest.getInstance("MD5");
    }

    /**
     * Rectifies all {@link Note}s in the account.
     *
     * @throws Exception
     */
    public void rectify() throws Exception {
        LOGGER.debug("getting notebooks");
        List<Notebook> notebooks = noteStore.listNotebooks(getAuthToken());
        LOGGER.trace("got {} notebook(s)", notebooks.size());

        for (Notebook notebook : notebooks) {
            rectify(notebook);
        }
    }

    /**
     * Rectifies the HTML <tt>img</tt>s in a single <tt>Notebook</tt>.
     *
     * @param notebook the <tt>Notebook</tt> containing the {@link Note}s to
     * rectify.
     * @throws Exception
     */
    public void rectify(Notebook notebook) throws Exception {
        LOGGER.debug("rectifying notebook {}: {}", notebook.getGuid(), notebook.getName());

        NoteFilter filter = new NoteFilter();
        filter.setNotebookGuid(notebook.getGuid());

        rectify(filter);
    }

    /**
     * Rectifies the HTML <tt>img</tt>s in <tt>Note</tt>s matching a
     * <tt>NoteFilter</tt>.
     *
     * @param filter a <tt>NoteFilter</tt> specifying the <tt>Note</tt>s to
     * rectify.
     * @throws Exception
     */
    public void rectify(NoteFilter filter) throws Exception {
        LOGGER.debug("rectifying notes matching filter");

        int start = 0;
        do {
            NoteList noteList = noteStore.findNotes(getAuthToken(), filter, start, 100);
            Iterator<Note> iter = noteList.getNotesIterator();
            while (iter.hasNext()) {
                Note note = iter.next();
                rectify(note);
            }

            if (noteList.getNotesSize() == 0) {
                start = -1;
            } else {
                start += noteList.getNotesSize();
            }
        } while (start >= 0);
    }

    /**
     * Rectifies the HTML <tt>img</tt>s in a single <tt>Note</tt>.
     *
     * @param note the <tt>Note</tt> to rectify.
     * @throws Exception
     */
    public void rectify(Note note) throws Exception {
        LOGGER.debug("rectifying note {}: {}", note.getGuid(), note.getTitle());

        String content = noteStore.getNoteContent(getAuthToken(), note.getGuid());
        LOGGER.info(content);
        ByteArrayInputStream bais = new ByteArrayInputStream(content.getBytes());
        Document doc = documentBuilder.parse(bais);
        NodeList imgs = doc.getElementsByTagName("img");

        LOGGER.debug("found {} img(s)", imgs.getLength());
        if (imgs.getLength() == 0) {
            return;
        }

        boolean updateNeeded = false;
        for (int i = 0; i < imgs.getLength(); i++) {
            Node img = imgs.item(i);
            NamedNodeMap attributes = img.getAttributes();
            String url = attributes.getNamedItem("src").getNodeValue();

            LOGGER.debug("fetching {}", url);
            Resource resource = makeResource(url);
            if (resource == null) {
                continue;
            } else {
                updateNeeded = true;
            }
            note.addToResources(resource);

            Element media = doc.createElement("en-media");
            populateEnMedia(media, resource, attributes);

            Node parent = img.getParentNode();
            parent.replaceChild(media, img);
        }

        if (updateNeeded) {
            doc.setXmlStandalone(true);
            Source source = new DOMSource(doc);

            StringWriter writer = new StringWriter();
            Result result = new StreamResult(writer);

            transformer.transform(source, result);

            note.setContentHashIsSet(false);
            note.setContentLengthIsSet(false);
            note.setContent(writer.getBuffer().toString());

            noteStore.updateNote(getAuthToken(), note);
        }
    }

    /**
     * Creates a <tt>Resource</tt> from the given URL.
     *
     * @param url the URL from which the resource's content should be obtained.
     * @return a <tt>Resource</tt> containing the URL's content, or
     * <tt>null</tt> if the URL could not be obtained (due to an unsupported
     * scheme, 404, etc.).
     * @throws IOException if an error occurs while fetching the resource.
     */
    private Resource makeResource(String url) throws IOException {
        if (url.startsWith("file://")) {
            LOGGER.warn("file:// scheme is not supported");
            return null;
        }

        GetMethod get = new GetMethod(url);
        if (httpClient.executeMethod(get) != 200) {
            LOGGER.warn("couldn't GET {}: {}", url, get.getStatusLine());
            get.releaseConnection();
            return null;
        }

        ResourceAttributes resourceAttributes = new ResourceAttributes();
        resourceAttributes.setAttachment(false);
        resourceAttributes.setClientWillIndex(false);
        resourceAttributes.setSourceURL(url);

        Data data = new Data();
        byte[] bytes = get.getResponseBody();
        data.setBody(bytes);
        md5.reset();
        byte[] hash = md5.digest(bytes);
        data.setBodyHash(hash);

        Header contentTypeHeader = get.getResponseHeader("Content-type");
        String contentType = contentTypeHeader.getValue();

        Resource resource = new Resource();
        resource.setAttributes(resourceAttributes);
        resource.setData(data);
        resource.setMime(contentType);

        return resource;
    }

    /**
     * Populates an <tt>en-media</tt> element's attributes with those of the
     * <tt>img</tt> it's replacing. The acceptable <tt>en-media</tt> attributes
     * are enumerated in <a
     * href="http://www.evernote.com/about/developer/api/evernote-api.htm#_Toc297053074">
     * Evernote API Overview, Evernote Markup Language (ENML), EN-MEDIA</a>.
     *
     * @param enMedia the <tt>en-media</tt> element.
     * @param resource the resource which this <tt>en-media</tt> element.
     * references.
     * @param attributes the attributes of the <tt>img</tt> being replaced.
     */
    private void populateEnMedia(Element enMedia, Resource resource, NamedNodeMap attributes) {
        byte[] hash = resource.getData().getBodyHash();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hash.length; i++) {
            sb.append(String.format("%02x", hash[i]));
        }

        // mandatory attributes
        enMedia.setAttribute("hash", sb.toString());
        enMedia.setAttribute("type", resource.getMime());

        // optional img attributes
        populateEnMediaAttribute(enMedia, attributes, "align");
        populateEnMediaAttribute(enMedia, attributes, "alt");
        populateEnMediaAttribute(enMedia, attributes, "longdesc");
        populateEnMediaAttribute(enMedia, attributes, "height");
        populateEnMediaAttribute(enMedia, attributes, "width");
        populateEnMediaAttribute(enMedia, attributes, "border");
        populateEnMediaAttribute(enMedia, attributes, "hspace");
        populateEnMediaAttribute(enMedia, attributes, "vspace");
        populateEnMediaAttribute(enMedia, attributes, "usemap");

        // optional XHTML attributes
        populateEnMediaAttribute(enMedia, attributes, "style");
        populateEnMediaAttribute(enMedia, attributes, "title");
        populateEnMediaAttribute(enMedia, attributes, "lang");
        populateEnMediaAttribute(enMedia, attributes, "xml:lang");
        populateEnMediaAttribute(enMedia, attributes, "dir");
    }

    private void populateEnMediaAttribute(Element enMedia, NamedNodeMap attributes, String name) {
        Node node = attributes.getNamedItem(name);
        if (node != null) {
            enMedia.setAttribute(name, node.getNodeValue());
        }
    }

    private String getAuthToken() throws EDAMUserException, EDAMSystemException, TException {
        if (authResultExpirationTime - System.currentTimeMillis() < TimeUnit.MINUTES.toMillis(15)) {
            LOGGER.debug("refreshing AuthenticationResult...");
            authResult = userStore.refreshAuthentication(authResult.getAuthenticationToken());
            authResultExpirationTime = System.currentTimeMillis() + authResult.getExpiration() - authResult.getCurrentTime();
            LOGGER.info("refreshed AuthenitcationResult; next expiration is at {}", authResultExpirationTime);
        }

        return authResult.getAuthenticationToken();
    }
}
