package com.tomppi.enderslicer.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CuraInputSecurityTest {
    @Test
    fun rejectsAcceptedArchiveDataBeyondTotalLimit() {
        val archive = zip(
            "Cura/a.cfg" to "a".repeat(40),
            "Cura/b.cfg" to "b".repeat(40),
        )

        val error = runCatching {
            CuraArchive.readTextEntries(
                input = ByteArrayInputStream(archive),
                maximumEntryBytes = 64,
                maximumTotalBytes = 64,
                accept = { it.startsWith("Cura/") },
            )
        }.exceptionOrNull()

        // Some ZipInputStream implementations wrap the limit exception while
        // closing the interrupted entry. The security contract is rejection.
        assertTrue(error != null)
    }

    @Test
    fun rejectsTooManyAcceptedArchiveEntries() {
        val archive = zip(
            "Cura/a.cfg" to "a",
            "Cura/b.cfg" to "b",
            "Cura/c.cfg" to "c",
        )

        val error = runCatching {
            CuraArchive.readTextEntries(
                input = ByteArrayInputStream(archive),
                maximumAcceptedEntries = 2,
                accept = { it.startsWith("Cura/") },
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("entries", ignoreCase = true))
    }

    @Test
    fun ignoredEntriesDoNotConsumeAcceptedDataLimit() {
        val archive = zip(
            "ignored.bin" to "x".repeat(512),
            "Cura/a.cfg" to "hello",
        )

        val entries = CuraArchive.readTextEntries(
            input = ByteArrayInputStream(archive),
            maximumEntryBytes = 16,
            maximumTotalBytes = 16,
            accept = { it.startsWith("Cura/") },
        )

        assertEquals(mapOf("Cura/a.cfg" to "hello"), entries)
    }

    @Test
    fun secureXmlRejectsDoctypeEntities() {
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE material [<!ENTITY leak SYSTEM "file:///etc/passwd">]>
            <material><name>&leak;</name></material>
        """.trimIndent()

        val error = runCatching {
            SecureXml.factory().newDocumentBuilder()
                .parse(ByteArrayInputStream(xml.toByteArray()))
        }.exceptionOrNull()

        assertTrue(error != null)
    }

    @Test
    fun secureXmlToleratesParserWithoutApacheDoctypeFeature() {
        // Android's Expat-based DocumentBuilderFactory rejects the Apache
        // disallow-doctype-decl feature even though it refuses DOCTYPEs
        // natively; profile import must not fail because of it.
        val unsupportedDoctypeFactory = object : javax.xml.parsers.DocumentBuilderFactory() {
            private val delegate = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            override fun newDocumentBuilder() = delegate.newDocumentBuilder()
            override fun setAttribute(name: String, value: Any) = delegate.setAttribute(name, value)
            override fun getAttribute(name: String): Any = delegate.getAttribute(name)
            override fun setFeature(name: String, value: Boolean) {
                if (name == "http://apache.org/xml/features/disallow-doctype-decl") {
                    throw javax.xml.parsers.ParserConfigurationException("unsupported feature")
                }
                delegate.setFeature(name, value)
            }
            override fun getFeature(name: String): Boolean = delegate.getFeature(name)
            override fun isNamespaceAware(): Boolean = delegate.isNamespaceAware()
            override fun setNamespaceAware(value: Boolean) = delegate.setNamespaceAware(value)
            override fun isValidating(): Boolean = delegate.isValidating()
            override fun setValidating(value: Boolean) = delegate.setValidating(value)
            override fun isXIncludeAware(): Boolean = delegate.isXIncludeAware()
            override fun setXIncludeAware(value: Boolean) = delegate.setXIncludeAware(value)
            override fun isExpandEntityReferences(): Boolean = delegate.isExpandEntityReferences()
            override fun setExpandEntityReferences(value: Boolean) = delegate.setExpandEntityReferences(value)
        }

        val factory = SecureXml.factory(unsupportedDoctypeFactory)
        assertTrue(factory.getFeature("http://xml.org/sax/features/external-general-entities") == false)
        assertTrue(factory.getFeature("http://xml.org/sax/features/external-parameter-entities") == false)
    }

    @Test
    fun secureXmlToleratesParserRejectingEveryHardeningFeature() {
        // Android Expat rejects even the SAX external-entity features; imports
        // must still succeed because that parser is secure by default.
        val strictParser = object : javax.xml.parsers.DocumentBuilderFactory() {
            private val delegate = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            override fun newDocumentBuilder() = delegate.newDocumentBuilder()
            override fun setAttribute(name: String, value: Any) {
                if (name.startsWith("http://javax.xml.XMLConstants/property/")) {
                    throw javax.xml.parsers.ParserConfigurationException("unsupported property")
                }
                delegate.setAttribute(name, value)
            }
            override fun getAttribute(name: String): Any = delegate.getAttribute(name)
            override fun setFeature(name: String, value: Boolean) {
                if (name.startsWith("http://")) {
                    throw javax.xml.parsers.ParserConfigurationException("unsupported feature")
                }
                delegate.setFeature(name, value)
            }
            override fun getFeature(name: String): Boolean {
                if (name.startsWith("http://")) {
                    throw javax.xml.parsers.ParserConfigurationException("unsupported feature")
                }
                return delegate.getFeature(name)
            }
            override fun isNamespaceAware(): Boolean = delegate.isNamespaceAware()
            override fun setNamespaceAware(value: Boolean) = delegate.setNamespaceAware(value)
            override fun isValidating(): Boolean = delegate.isValidating()
            override fun setValidating(value: Boolean) = delegate.setValidating(value)
            override fun isXIncludeAware(): Boolean = delegate.isXIncludeAware()
            override fun setXIncludeAware(value: Boolean) = delegate.setXIncludeAware(value)
            override fun isExpandEntityReferences(): Boolean = delegate.isExpandEntityReferences()
            override fun setExpandEntityReferences(value: Boolean) = delegate.setExpandEntityReferences(value)
        }

        assertTrue(SecureXml.factory(strictParser).newDocumentBuilder() != null)
    }

    @Test
    fun secureXmlRejectsParserThatAcceptsButDoesNotApplyHardening() {
        // The false-assurance case: a parser that claims a feature but leaves
        // the parser vulnerable must still be rejected loudly.
        val lyingParser = object : javax.xml.parsers.DocumentBuilderFactory() {
            private val delegate = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            override fun newDocumentBuilder() = delegate.newDocumentBuilder()
            override fun setAttribute(name: String, value: Any) = delegate.setAttribute(name, value)
            override fun getAttribute(name: String): Any = delegate.getAttribute(name)
            override fun setFeature(name: String, value: Boolean) = delegate.setFeature(name, value)
            override fun getFeature(name: String): Boolean {
                if (name == "http://xml.org/sax/features/external-general-entities") return true
                return delegate.getFeature(name)
            }
            override fun isNamespaceAware(): Boolean = delegate.isNamespaceAware()
            override fun setNamespaceAware(value: Boolean) = delegate.setNamespaceAware(value)
            override fun isValidating(): Boolean = delegate.isValidating()
            override fun setValidating(value: Boolean) = delegate.setValidating(value)
            override fun isXIncludeAware(): Boolean = delegate.isXIncludeAware()
            override fun setXIncludeAware(value: Boolean) = delegate.setXIncludeAware(value)
            override fun isExpandEntityReferences(): Boolean = delegate.isExpandEntityReferences()
            override fun setExpandEntityReferences(value: Boolean) = delegate.setExpandEntityReferences(value)
        }

        val error = runCatching { SecureXml.factory(lyingParser) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("did not apply"))
    }

    @Test
    fun readsNormalAcceptedEntries() {
        val archive = zip("Cura/a.cfg" to "hello")
        val entries = CuraArchive.readTextEntries(
            ByteArrayInputStream(archive),
            accept = { it.startsWith("Cura/") },
        )
        assertEquals("hello", entries["Cura/a.cfg"])
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
