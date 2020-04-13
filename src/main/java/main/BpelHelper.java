package main;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import javax.xml.xpath.XPath;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.w3c.dom.*;
import org.xml.sax.SAXException;

public class BpelHelper {

    private final String microflowPath;

    private List<Document> bpels;
    private Map<String, String> untreatedComponents;
    private Transformer transformer;
    private DocumentBuilder documentBuilder;
    private int countOfDeletedPartners = 0;

    public BpelHelper(String microflowPath) throws ParserConfigurationException, IOException, TransformerConfigurationException {
        this.microflowPath = microflowPath;
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        this.documentBuilder = dbf.newDocumentBuilder();
        Stream<Path> paths = Files.walk(Paths.get(this.microflowPath));
        Optional<List<Document>> documents = Optional.of(
                paths
                        .map(Path::toFile)
                        .filter(file -> file.getName().endsWith(".bpel"))
                        .map(file -> strictParseFile(file, documentBuilder))
                        .collect(Collectors.toList()));
        bpels = documents.orElseThrow(() -> new RuntimeException("No required files found"));
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        this.transformer = transformerFactory.newTransformer();
        this.transformer.setOutputProperty(OutputKeys.INDENT, "no");
        this.transformer.setOutputProperty(OutputKeys.ENCODING, bpels.get(0).getXmlEncoding());
        untreatedComponents = new HashMap<>();
    }

    private Document strictParseFile(File file, DocumentBuilder documentBuilder) {
        try {
            return documentBuilder.parse(file);
        } catch (Exception e) {
            throw new RuntimeException("problems during parsing file: " + file.getName());
        }
    }

    private Document parseFile(File file, DocumentBuilder documentBuilder) {
        try {
            return documentBuilder.parse(file);
        } catch (SAXException | IOException e) {
            untreatedComponents.put(file.getAbsolutePath(), "Error during parsing file");
            return null;
        }
    }

    public void removeExcessPartners() throws XPathExpressionException, TransformerException {
        boolean partnerExists;
        boolean needToSave;
        for (Document bpel:bpels) {
            needToSave = false;
            NodeList partners = getPartnersFromBpel(bpel);
            NodeList invokes = getInvokesFromBpel(bpel);
            System.out.println("analyze " + bpel.getDocumentURI() + ": partners = " + partners.getLength() + ", invokes = " + invokes.getLength());
            for (int i = 0; i < partners.getLength(); i++) {
                Node partner = partners.item(i);
                partnerExists = false;
                NamedNodeMap partnerAttributes = partner.getAttributes();
                String partnerNameAttr = partnerAttributes.getNamedItem("name").getTextContent();
                Optional<Node> myRoleAttr = Optional.ofNullable(partnerAttributes.getNamedItem("myRole"));
                for (int j = 0; j < invokes.getLength(); j++) {
                    if (myRoleAttr.isPresent()) {
                        partnerExists = true;
                        System.out.println("ignore interface " + partnerNameAttr);
                        break;
                    }
                    Optional<String> partnerLinkAttr = Optional.ofNullable(invokes.item(j).getAttributes().getNamedItem("partnerLink").getTextContent());
                    if (partnerNameAttr.equalsIgnoreCase(partnerLinkAttr.orElse(""))) {
                        System.out.println(partnerNameAttr + " used");
                        partnerExists = true;
                        break;
                    }
                }
                if (!partnerExists) {
                    System.out.println("remove " + partnerNameAttr);
                    partner.getParentNode().removeChild(partner);
                    countOfDeletedPartners++;
                    deleteReferenceFromComponent(bpel, partnerNameAttr);
                    deletePartnerLinkTypeFromWsdl(bpel, partnerAttributes);
                    needToSave = true;
                }
            }
            if (needToSave) {
                doOperationWithPartnerLinksTag(bpel);
                saveXml(bpel);
            }
        }
        System.out.println("Success! Count of deleted partners = " + countOfDeletedPartners);
        System.out.println("Check errors:");
        untreatedComponents.forEach((key, value) -> System.out.println(key + " : " + value));
    }

    private void deletePartnerLinkTypeFromWsdl(Document bpel, NamedNodeMap partnerAttributes) {
        try {
            deletePartnerLinkType(getWsdlDocument(bpel), partnerAttributes.getNamedItem("partnerLinkType").getTextContent().split(":")[1]);
        } catch (IOException e) {
            untreatedComponents.put(bpel.getDocumentURI(), "Error during delete partnerLinkType");
        }
    }

    private void deletePartnerLinkType(Document wsdlDocument, String partnerLinkTypeName) {
        NodeList partnerLinkTypeTags = wsdlDocument.getElementsByTagName("plnk:partnerLinkType");
        int i = 0;
        while (i< partnerLinkTypeTags.getLength()) {
            Node partnerLinkType = partnerLinkTypeTags.item(i);
            NamedNodeMap partnerLinkTypeAttributes = partnerLinkType.getAttributes();
            if (partnerLinkTypeName.equals(partnerLinkTypeAttributes.getNamedItem("name").getTextContent())) {
                partnerLinkType.getParentNode().removeChild(partnerLinkType);
                removeNamespaceFromWsdl(partnerLinkType, wsdlDocument);
                try {
                    saveXml(wsdlDocument);
                } catch (TransformerException e) {
                    untreatedComponents.put(wsdlDocument.getDocumentURI(), "Error during save xml");
                }
                System.out.println("delete partnerLinkType in " + wsdlDocument.getDocumentURI() +  " was successful");
                break;
            }
            i++;
        }
        if (i == partnerLinkTypeTags.getLength()) System.out.println("partnerLinkType not found");
    }

    private void removeNamespaceFromWsdl(Node partnerLinkType, Document componentDocument) {
        System.out.println("remove namespace in wsdl");
        String prefix = searchNSPrefixWsdl(partnerLinkType);
        Element namespaces = (Element) Optional.ofNullable(componentDocument.getElementsByTagName("wsdl:definitions").item(0))
                .orElse(componentDocument.getElementsByTagName("definitions").item(0));
        Node namespaceForRemove = namespaces.getAttributes().getNamedItem("xmlns:" + prefix);
        namespaces.removeAttribute(namespaceForRemove.getNodeName());
        System.out.println("remove wsdl namespace success");
    }

    private String searchNSPrefixWsdl(Node partnerLinkType) {
        NodeList referenceChilds = partnerLinkType.getChildNodes();
        int i = 0;
        while (i < referenceChilds.getLength()) {
            Node referenceChild = referenceChilds.item(i);
            if (referenceChild.getNodeName().equals("plnk:role")) {
                NodeList plnkRoleChilds = referenceChild.getChildNodes();
                int j = 0;
                while (j < plnkRoleChilds.getLength()) {
                    Node plnkRoleChild = plnkRoleChilds.item(j);
                    if (plnkRoleChild.getNodeName().equals("plnk:portType"))
                        return plnkRoleChild.getAttributes().getNamedItem("name").getTextContent().split(":")[0];
                    j++;
                }
            }
            i++;
        }
        return null;
    }

    private void deleteReferenceFromComponent(Document bpel, String partnerName) {
        try {
            deleteReference(getComponentDocument(bpel), partnerName);
        } catch (IOException e) {
            untreatedComponents.put(bpel.getDocumentURI(), "Error during delete reference");
        }

    }

    private void deleteReference(Document componentDocument, String partnerName) {
        Optional.of(componentDocument).ifPresent((document) -> {
            System.out.println("delete reference " + partnerName);
            NodeList references = document.getElementsByTagName("reference");
            int i = 0;
            while (i < references.getLength()) {
                Node reference = references.item(i);
                NamedNodeMap referenceAttributes = reference.getAttributes();
                String referenceName = referenceAttributes.getNamedItem("name").getTextContent();
                if (referenceName.equalsIgnoreCase(partnerName)) {
                    reference.getParentNode().removeChild(reference);
                    removeNamespaceFromComponent(reference, componentDocument);
                    try {
                        //removeEmptyLinesFromXml(document.getElementsByTagName("references").item(0));
                        saveXml(document);
                    } catch (TransformerException e) {
                        untreatedComponents.put(document.getDocumentURI(), "Error during save xml");
                    }
                    System.out.println("delete reference " + document.getDocumentURI() +  " was successful");
                    break;
                }
                i++;

            }
            if (i == references.getLength()) System.out.println("reference not found");
        });
    }

    private void removeNamespaceFromComponent(Node reference, Document componentDocument) {
        System.out.println("remove namespace in component");
        String prefix = searchNSPrefixComponent(reference);
        Element namespaces = (Element) componentDocument.getElementsByTagName("scdl:component").item(0);
        Node namespaceForRemove = namespaces.getAttributes().getNamedItem("xmlns:" + prefix);
        namespaces.removeAttribute(namespaceForRemove.getNodeName());
        //System.out.println("remove component namespace success");
    }

    private String searchNSPrefixComponent(Node reference) {
        NodeList referenceChilds = reference.getChildNodes();
        int i = 0;
        while (i < referenceChilds.getLength()) {
            Node child = referenceChilds.item(i);
            if (child.getNodeName().equals("interface"))
                return child.getAttributes().getNamedItem("portType").getTextContent().split(":")[0];
            i++;
        }
        return null;
    }

    private Document getComponentDocument(Document bpel) throws IOException {
        Document componentDocument = null;
        String bpelPath = bpel.getDocumentURI();
        String[] splitedBpelPath = bpelPath.split("/");
        String bpelName = splitedBpelPath[splitedBpelPath.length - 1].replace(".bpel", "");
        String componentExtension = ".component";
        Path componentPath = Paths.get(microflowPath + File.separator + bpelName + componentExtension);
        Optional<File> componentFile = Optional.of(componentPath.toFile());
        return componentFile.map(file -> parseFile(file, documentBuilder)).orElseThrow(IOException::new);
    }

    private Document getWsdlDocument(Document bpel) throws IOException {
        Document wsdlDocument = null;
        Path wsdlPath = Paths.get(bpel.getDocumentURI().replace("file:/", "").replace(".bpel", "Artifacts.wsdl"));
        Optional<File> componentFile = Optional.of(wsdlPath.toFile());
        return componentFile.map(file -> parseFile(file, documentBuilder)).orElseThrow(IOException::new);
    }

    private void doOperationWithPartnerLinksTag(Document bpel) {
        System.out.println("check bpws:partnerLinks size");
        Node partnerLinksTag = bpel.getElementsByTagName("bpws:partnerLinks").item(0);
        if (getPartnersFromBpel(bpel).getLength() == 0) {
            System.out.println("delete bpws:partnerLinks");
            partnerLinksTag.getParentNode().removeChild(partnerLinksTag);
        } else {
            //removeEmptyLinesFromXml(partnerLinksTag);
        }
    }

    private void saveXml(Document xml) throws TransformerException {
        String xmlPath = xml.getDocumentURI();
        System.out.println("save " + xmlPath);
        xml.normalize();
        xml.setXmlStandalone(true);
        DOMSource source = new DOMSource(xml);
        StreamResult result = new StreamResult(new File(xmlPath.replace("file:", "")));
        transformer.transform(source, result);
    }

    private void removeEmptyLinesFromXml(Node tag) throws XPathExpressionException {
        XPath xp = XPathFactory.newInstance().newXPath();
        NodeList nl = (NodeList) xp.evaluate("//text()[normalize-space(.)='']", tag, XPathConstants.NODESET);

        for (int i=0; i < nl.getLength(); ++i) {
            Node node = nl.item(i);
            node.getParentNode().removeChild(node);
        }
    }

    private NodeList getPartnersFromBpel(Document bpel) {
        return bpel.getElementsByTagName("bpws:partnerLink");
    }

    private NodeList getInvokesFromBpel(Document bpel) {
        return bpel.getElementsByTagName("bpws:invoke");
    }
}