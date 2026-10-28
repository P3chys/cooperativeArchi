package io.github.p3chys.cooperativearchi.xml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** Parses XML into {@link XNode} trees and writes them back in a deterministic, diff-friendly form. */
public final class Xml {

    private static final List<String> LEADING_ATTRS = List.of("xsi:type", "name", "id");

    // a split model is thousands of small files: setting up a parser costs more than parsing one, so reuse it
    private static final ThreadLocal<DocumentBuilder> BUILDER = ThreadLocal.withInitial(Xml::newBuilder);

    private Xml() {
    }

    public static XNode parse(byte[] bytes) throws IOException {
        DocumentBuilder builder = BUILDER.get();
        try {
            builder.setErrorHandler(null);
            return convert(builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement());
        } catch (SAXException e) {
            throw new IOException("Invalid XML: " + e.getMessage(), e);
        } finally {
            builder.reset();
        }
    }

    private static DocumentBuilder newBuilder() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static XNode convert(Element element) {
        Map<String, String> attrs = new LinkedHashMap<>();
        NamedNodeMap map = element.getAttributes();
        for (int i = 0; i < map.getLength(); i++) {
            Node attr = map.item(i);
            attrs.put(attr.getNodeName(), attr.getNodeValue());
        }
        List<XNode> children = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        NodeList nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element child) {
                children.add(convert(child));
            } else if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(node.getNodeValue());
            }
        }
        return new XNode(element.getTagName(), canonicalOrder(attrs), children.isEmpty() ? text.toString() : "", children);
    }

    /** xmlns declarations, then xsi:type, name, id, then everything else alphabetically. */
    public static Map<String, String> canonicalOrder(Map<String, String> attrs) {
        Map<String, String> result = new LinkedHashMap<>();
        new TreeMap<>(attrs).forEach((k, v) -> {
            if (k.startsWith("xmlns")) {
                result.put(k, v);
            }
        });
        for (String key : LEADING_ATTRS) {
            if (attrs.containsKey(key)) {
                result.put(key, attrs.get(key));
            }
        }
        new TreeMap<>(attrs).forEach(result::putIfAbsent);
        return result;
    }

    public static byte[] write(XNode root) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        write(sb, root, 0);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void write(StringBuilder sb, XNode node, int depth) {
        sb.append("  ".repeat(depth)).append('<').append(node.tag());
        canonicalOrder(node.attrs()).forEach((k, v) -> sb.append(' ').append(k).append("=\"").append(escape(v, true)).append('"'));
        if (!node.children().isEmpty()) {
            sb.append(">\n");
            for (XNode child : node.children()) {
                write(sb, child, depth + 1);
            }
            sb.append("  ".repeat(depth)).append("</").append(node.tag()).append(">\n");
        } else if (!node.text().isEmpty()) {
            sb.append('>').append(escape(node.text(), false)).append("</").append(node.tag()).append(">\n");
        } else {
            sb.append("/>\n");
        }
    }

    private static String escape(String s, boolean attribute) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append(attribute ? "&quot;" : "\"");
                case '\r' -> sb.append("&#xD;");
                case '\n' -> sb.append(attribute ? "&#xA;" : "\n");
                case '\t' -> sb.append(attribute ? "&#x9;" : "\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
