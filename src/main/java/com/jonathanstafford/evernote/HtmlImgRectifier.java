package com.jonathanstafford.evernote;

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
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
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
    private final AuthenticationResult authResult;
    private final NoteStore.Client noteStore;
    private final DocumentBuilder documentBuilder;
    private final Transformer transformer;
    private final HttpClient httpClient;
    private final MessageDigest md5;

    public HtmlImgRectifier(UserStore.Client userStore, AuthenticationResult authResult, NoteStore.Client noteStore) throws TException, ParserConfigurationException, NoSuchAlgorithmException, TransformerConfigurationException {
        this.userStore = userStore;
        this.authResult = authResult;
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

    public void rectify() throws Exception {
        LOGGER.debug("getting notebooks");
        List<Notebook> notebooks = noteStore.listNotebooks(authResult.getAuthenticationToken());
        LOGGER.trace("got {} notebook(s)", notebooks.size());

        for (Notebook notebook : notebooks) {
            rectify(notebook);
        }
    }

    public void rectify(Notebook notebook) throws Exception {
        LOGGER.debug("rectifying notebook {}: {}", notebook.getGuid(), notebook.getName());

        NoteFilter filter = new NoteFilter();
        filter.setNotebookGuid(notebook.getGuid());

        rectify(filter);
    }

    public void rectify(NoteFilter filter) throws Exception {
        LOGGER.debug("rectifying notes matching filter");

        int start = 0;
        do {
            NoteList noteList = noteStore.findNotes(authResult.getAuthenticationToken(), filter, start, 100);
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

    public void rectify(Note note) throws Exception {
        LOGGER.debug("rectifying note {}: {}", note.getGuid(), note.getTitle());

        String content = noteStore.getNoteContent(authResult.getAuthenticationToken(), note.getGuid());
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

            noteStore.updateNote(authResult.getAuthenticationToken(), note);
        }
    }

    private Resource makeResource(String url) throws IOException, URISyntaxException {
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

    private void populateEnMedia(Element enMedia, Resource resource, NamedNodeMap attributes) {
        byte[] hash = resource.getData().getBodyHash();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hash.length; i++) {
            sb.append(String.format("%02x", hash[i]));
        }

        enMedia.setAttribute("hash", sb.toString());
        enMedia.setAttribute("type", resource.getMime());

        populateEnMediaAttribute(enMedia, attributes, "align");
        populateEnMediaAttribute(enMedia, attributes, "alt");
        populateEnMediaAttribute(enMedia, attributes, "longdesc");
        populateEnMediaAttribute(enMedia, attributes, "height");
        populateEnMediaAttribute(enMedia, attributes, "width");
        populateEnMediaAttribute(enMedia, attributes, "border");
        populateEnMediaAttribute(enMedia, attributes, "hspace");
        populateEnMediaAttribute(enMedia, attributes, "vspace");
        populateEnMediaAttribute(enMedia, attributes, "usemap");

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
}
