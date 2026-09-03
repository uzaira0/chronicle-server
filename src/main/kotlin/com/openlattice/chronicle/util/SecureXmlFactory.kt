/*
 * Copyright (C) 2024. Chronicle.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.openlattice.chronicle.util

import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import java.io.InputStream
import java.io.Reader
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamReader
import javax.xml.transform.Transformer
import javax.xml.transform.TransformerFactory
import javax.xml.validation.SchemaFactory

/**
 * Factory object for creating secure XML parsers with XXE (XML External Entity) protection.
 *
 * XXE attacks exploit vulnerable XML parsers to:
 * - Read arbitrary files from the server (file:// protocol)
 * - Perform Server-Side Request Forgery (SSRF) via http:// entities
 * - Cause Denial of Service via entity expansion ("billion laughs" attack)
 * - Exfiltrate data to attacker-controlled servers
 *
 * This factory creates XML parsers with the following protections:
 * 1. External entities disabled
 * 2. External DTD loading disabled
 * 3. DOCTYPE declarations blocked
 * 4. Entity expansion limited
 * 5. External parameter entities disabled
 *
 * Usage:
 *   val doc = SecureXmlFactory.parseDocument(xmlString)
 *   val reader = SecureXmlFactory.createXmlStreamReader(inputStream)
 *
 * IMPORTANT: Always use this factory when parsing XML from untrusted sources.
 * Never create XML parsers manually without these security settings.
 *
 * CVEs Mitigated:
 * - CWE-611: Improper Restriction of XML External Entity Reference
 * - CWE-776: Improper Restriction of Recursive Entity References in DTDs
 * - Various XXE-related CVEs in specific XML libraries
 */
// reason: single secure-XML factory facade — the parser/stream/transformer/schema builders and
// their validation helpers are one cohesive XXE-hardening API and belong together
@Suppress("TooManyFunctions")
public object SecureXmlFactory {

    private val logger = LoggerFactory.getLogger(SecureXmlFactory::class.java)

    // Feature URIs for XML security
    private const val FEATURE_DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl"
    private const val FEATURE_EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities"
    private const val FEATURE_EXTERNAL_PARAMETER_ENTITIES = "http://xml.org/sax/features/external-parameter-entities"
    private const val FEATURE_LOAD_EXTERNAL_DTD = "http://apache.org/xml/features/nonvalidating/load-external-dtd"
    private const val FEATURE_SECURE_PROCESSING = XMLConstants.FEATURE_SECURE_PROCESSING

    /**
     * Exception thrown when an XML document contains prohibited constructs
     * such as DOCTYPE declarations or external entities.
     */
    public class XmlSecurityException(message: String, cause: Throwable? = null) : SecurityException(message, cause)

    /**
     * Creates a secure DocumentBuilderFactory with XXE protections.
     *
     * @param allowDtd If true, allows DOCTYPE declarations but still disables external entities.
     *                 Should only be true if you explicitly need DTD validation with trusted DTDs.
     *                 Default is false for maximum security.
     * @return A security-hardened DocumentBuilderFactory
     * @throws ParserConfigurationException if a security feature cannot be set
     */
    @JvmStatic
    @JvmOverloads
    @Throws(ParserConfigurationException::class)
    public fun createDocumentBuilderFactory(allowDtd: Boolean = false): DocumentBuilderFactory {
        val factory = DocumentBuilderFactory.newInstance() // nosemgrep: chronicle-xxe-document-builder,chronicle-xml-entity-expansion

        try {
            // CRITICAL: Disable DOCTYPE declarations entirely (most secure option)
            if (!allowDtd) {
                factory.setFeature(FEATURE_DISALLOW_DOCTYPE, true)
            }

            // Disable external entities
            factory.setFeature(FEATURE_EXTERNAL_GENERAL_ENTITIES, false)
            factory.setFeature(FEATURE_EXTERNAL_PARAMETER_ENTITIES, false)

            // Disable external DTD loading
            factory.setFeature(FEATURE_LOAD_EXTERNAL_DTD, false)

            // Enable secure processing mode
            factory.setFeature(FEATURE_SECURE_PROCESSING, true)

            // Additional security attributes
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")

            // Namespace awareness (optional but often needed)
            factory.isNamespaceAware = true

            // Disable XInclude processing
            factory.isXIncludeAware = false

            // Disable validation against DTD (we don't load DTDs anyway)
            factory.isValidating = false

            // Expand entity references (but only internal ones since external are disabled)
            factory.isExpandEntityReferences = false

            logger.debug("Created secure DocumentBuilderFactory with XXE protections enabled")

        } catch (e: ParserConfigurationException) {
            logger.error("Failed to configure secure DocumentBuilderFactory: ${e.message}")
            throw ParserConfigurationException(
                "Unable to create secure XML parser. Required security features not supported: ${e.message}"
            ).apply { initCause(e) }
        }

        return factory
    }

    /**
     * Creates a secure DocumentBuilder for parsing XML documents.
     *
     * @param allowDtd If true, allows DOCTYPE declarations (use with caution)
     * @return A security-hardened DocumentBuilder
     */
    @JvmStatic
    @JvmOverloads
    @Throws(ParserConfigurationException::class)
    public fun createDocumentBuilder(allowDtd: Boolean = false): DocumentBuilder {
        return createDocumentBuilderFactory(allowDtd).newDocumentBuilder()
    }

    /**
     * Parses an XML string into a Document with full XXE protection.
     *
     * @param xml The XML string to parse
     * @return A parsed Document object
     * @throws XmlSecurityException if the XML contains DOCTYPE or external entities
     */
    @JvmStatic
    @Throws(XmlSecurityException::class)
    public fun parseDocument(xml: String): Document {
        return parseDocument(StringReader(xml))
    }

    /**
     * Parses XML from a Reader into a Document with full XXE protection.
     *
     * @param reader The Reader providing XML content
     * @return A parsed Document object
     * @throws XmlSecurityException if the XML contains DOCTYPE or external entities
     */
    @JvmStatic
    @Throws(XmlSecurityException::class)
    public fun parseDocument(reader: Reader): Document {
        return parseDocument(InputSource(reader))
    }

    /**
     * Parses XML from an InputStream into a Document with full XXE protection.
     *
     * @param inputStream The InputStream providing XML content
     * @return A parsed Document object
     * @throws XmlSecurityException if the XML contains DOCTYPE or external entities
     */
    @JvmStatic
    @Throws(XmlSecurityException::class)
    public fun parseDocument(inputStream: InputStream): Document {
        return parseDocument(InputSource(inputStream))
    }

    /**
     * Parses XML from an InputSource into a Document with full XXE protection.
     *
     * @param inputSource The InputSource providing XML content
     * @return A parsed Document object
     * @throws XmlSecurityException if the XML contains DOCTYPE or external entities
     */
    @JvmStatic
    @Throws(XmlSecurityException::class)
    public fun parseDocument(inputSource: InputSource): Document {
        try {
            val builder = createDocumentBuilder(allowDtd = false)
            return builder.parse(inputSource)
        } catch (e: SAXParseException) {
            val message = "XML parsing failed at line ${e.lineNumber}, column ${e.columnNumber}: ${e.message}"
            logger.warn("Blocked potentially malicious XML: $message")
            throw XmlSecurityException(message, e)
        } catch (e: SAXException) {
            val message = "XML parsing failed: ${e.message}"
            logger.warn("Blocked potentially malicious XML: $message")
            throw XmlSecurityException(message, e)
        } catch (e: ParserConfigurationException) {
            throw XmlSecurityException("Failed to create secure XML parser: ${e.message}", e)
        }
    }

    /**
     * Creates a secure SAXParserFactory with XXE protections.
     *
     * @param allowDtd If true, allows DOCTYPE declarations (use with caution)
     * @return A security-hardened SAXParserFactory
     */
    // reason: boundary catch — setFeature can raise several SAX/parser exception types; all are
    // normalized to a ParserConfigurationException (cause chained) so the secure factory never
    // returns misconfigured
    @Suppress("TooGenericExceptionCaught")
    @JvmStatic
    @JvmOverloads
    @Throws(ParserConfigurationException::class, SAXException::class)
    public fun createSaxParserFactory(allowDtd: Boolean = false): SAXParserFactory {
        val factory = SAXParserFactory.newInstance() // nosemgrep: chronicle-xxe-sax-parser

        try {
            if (!allowDtd) {
                factory.setFeature(FEATURE_DISALLOW_DOCTYPE, true)
            }
            factory.setFeature(FEATURE_EXTERNAL_GENERAL_ENTITIES, false)
            factory.setFeature(FEATURE_EXTERNAL_PARAMETER_ENTITIES, false)
            factory.setFeature(FEATURE_LOAD_EXTERNAL_DTD, false)
            factory.setFeature(FEATURE_SECURE_PROCESSING, true)

            factory.isNamespaceAware = true
            factory.isValidating = false
            factory.isXIncludeAware = false

            logger.debug("Created secure SAXParserFactory with XXE protections enabled")

        } catch (e: Exception) {
            logger.error("Failed to configure secure SAXParserFactory: ${e.message}")
            throw ParserConfigurationException(
                "Unable to create secure SAX parser: ${e.message}"
            ).apply { initCause(e) }
        }

        return factory
    }

    /**
     * Creates a secure SAXParser with XXE protections.
     *
     * @param allowDtd If true, allows DOCTYPE declarations (use with caution)
     * @return A security-hardened SAXParser
     */
    @JvmStatic
    @JvmOverloads
    @Throws(ParserConfigurationException::class, SAXException::class)
    public fun createSaxParser(allowDtd: Boolean = false): SAXParser {
        return createSaxParserFactory(allowDtd).newSAXParser()
    }

    /**
     * Creates a secure XMLInputFactory for StAX parsing with XXE protections.
     *
     * @return A security-hardened XMLInputFactory
     */
    @JvmStatic
    public fun createXmlInputFactory(): XMLInputFactory {
        val factory = XMLInputFactory.newInstance() // nosemgrep: chronicle-xxe-xml-input-factory

        // Disable DTD processing
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)

        // Disable external entities
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)

        // Disable entity reference resolution
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)

        // Use secure defaults
        try {
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        } catch (e: IllegalArgumentException) {
            // Property not supported by this implementation, which is fine
            logger.debug("XMLInputFactory does not support ACCESS_EXTERNAL_* properties: {}", e.message)
        }

        logger.debug("Created secure XMLInputFactory with XXE protections enabled")
        return factory
    }

    /**
     * Creates a secure XMLStreamReader for parsing XML with XXE protections.
     *
     * @param inputStream The InputStream providing XML content
     * @return A security-hardened XMLStreamReader
     */
    @JvmStatic
    public fun createXmlStreamReader(inputStream: InputStream): XMLStreamReader {
        return createXmlInputFactory().createXMLStreamReader(inputStream)
    }

    /**
     * Creates a secure XMLStreamReader for parsing XML with XXE protections.
     *
     * @param reader The Reader providing XML content
     * @return A security-hardened XMLStreamReader
     */
    @JvmStatic
    public fun createXmlStreamReader(reader: Reader): XMLStreamReader {
        return createXmlInputFactory().createXMLStreamReader(reader)
    }

    /**
     * Creates a secure XMLStreamReader for parsing XML with XXE protections.
     *
     * @param xml The XML string to parse
     * @return A security-hardened XMLStreamReader
     */
    @JvmStatic
    public fun createXmlStreamReader(xml: String): XMLStreamReader {
        return createXmlStreamReader(StringReader(xml))
    }

    /**
     * Creates a secure TransformerFactory with XXE protections.
     *
     * @return A security-hardened TransformerFactory
     */
    // reason: boundary catch — setFeature/setAttribute may raise different unsupported-property
    // exception types across XML implementations; best-effort hardening logs and continues
    @Suppress("TooGenericExceptionCaught")
    @JvmStatic
    public fun createTransformerFactory(): TransformerFactory {
        val factory = TransformerFactory.newInstance()

        try {
            factory.setFeature(FEATURE_SECURE_PROCESSING, true)
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
        } catch (e: Exception) {
            logger.warn("Could not set all security attributes on TransformerFactory: ${e.message}")
        }

        logger.debug("Created secure TransformerFactory with XXE protections enabled")
        return factory
    }

    /**
     * Creates a secure Transformer for XSLT processing.
     *
     * @return A security-hardened Transformer
     */
    @JvmStatic
    public fun createTransformer(): Transformer {
        return createTransformerFactory().newTransformer()
    }

    /**
     * Creates a secure SchemaFactory for XSD validation with XXE protections.
     *
     * @return A security-hardened SchemaFactory
     */
    // reason: boundary catch — setFeature/setProperty may raise different unsupported-property
    // exception types across XML implementations; best-effort hardening logs and continues
    @Suppress("TooGenericExceptionCaught")
    @JvmStatic
    public fun createSchemaFactory(): SchemaFactory {
        val factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)

        try {
            factory.setFeature(FEATURE_SECURE_PROCESSING, true)
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        } catch (e: Exception) {
            logger.warn("Could not set all security attributes on SchemaFactory: ${e.message}")
        }

        logger.debug("Created secure SchemaFactory with XXE protections enabled")
        return factory
    }

    /**
     * Validates that an XML string does not contain DOCTYPE declarations.
     * This is a fast preliminary check before full parsing.
     *
     * @param xml The XML string to check
     * @throws XmlSecurityException if DOCTYPE is found
     */
    @JvmStatic
    @Throws(XmlSecurityException::class)
    public fun validateNoDoctype(xml: String) {
        // Quick regex check for DOCTYPE (case insensitive)
        val doctypePattern = Regex("<!DOCTYPE", RegexOption.IGNORE_CASE)
        if (doctypePattern.containsMatchIn(xml)) {
            logger.warn("Blocked XML containing DOCTYPE declaration")
            throw XmlSecurityException("DOCTYPE declarations are not allowed for security reasons")
        }
    }

    /**
     * Validates that an XML string does not contain entity declarations.
     * This is a fast preliminary check before full parsing.
     *
     * @param xml The XML string to check
     * @throws XmlSecurityException if ENTITY is found
     */
    @JvmStatic
    @Throws(XmlSecurityException::class)
    public fun validateNoEntities(xml: String) {
        // Quick regex check for ENTITY declarations
        val entityPattern = Regex("<!ENTITY", RegexOption.IGNORE_CASE)
        if (entityPattern.containsMatchIn(xml)) {
            logger.warn("Blocked XML containing ENTITY declaration")
            throw XmlSecurityException("ENTITY declarations are not allowed for security reasons")
        }
    }

    /**
     * Performs all preliminary security validation on an XML string.
     *
     * @param xml The XML string to validate
     * @throws XmlSecurityException if any prohibited content is found
     */
    @JvmStatic
    @Throws(XmlSecurityException::class)
    public fun validateXmlSecurity(xml: String) {
        validateNoDoctype(xml)
        validateNoEntities(xml)
    }

    /**
     * Safely parses XML with both preliminary validation and secure parser.
     * This provides defense-in-depth.
     *
     * @param xml The XML string to parse
     * @return A parsed Document object
     * @throws XmlSecurityException if the XML fails security validation
     */
    @JvmStatic
    @Throws(XmlSecurityException::class)
    public fun safeParseDocument(xml: String): Document {
        // First, quick preliminary check
        validateXmlSecurity(xml)

        // Then, use the secure parser
        return parseDocument(xml)
    }
}
