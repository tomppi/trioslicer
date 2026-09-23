package com.tomppi.enderslicer.profile

import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal object SecureXml {
    private const val FEATURE_DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl"
    private const val FEATURE_EXTERNAL_GENERAL = "http://xml.org/sax/features/external-general-entities"
    private const val FEATURE_EXTERNAL_PARAMETER = "http://xml.org/sax/features/external-parameter-entities"
    private const val FEATURE_LOAD_EXTERNAL_DTD = "http://apache.org/xml/features/nonvalidating/load-external-dtd"
    private const val PROPERTY_ACCESS_EXTERNAL_DTD = "http://javax.xml.XMLConstants/property/accessExternalDTD"
    private const val PROPERTY_ACCESS_EXTERNAL_SCHEMA = "http://javax.xml.XMLConstants/property/accessExternalSchema"

    fun factory(namespaceAware: Boolean = true): DocumentBuilderFactory =
        factory(DocumentBuilderFactory.newInstance(), namespaceAware)

    internal fun factory(
        base: DocumentBuilderFactory,
        namespaceAware: Boolean = true,
    ): DocumentBuilderFactory = base.apply {
        isNamespaceAware = namespaceAware
        runCatching { isXIncludeAware = false }
        // Hardening is applied where the parser supports it. A parser that
        // accepts a feature but fails to apply it is rejected loudly; a parser
        // that rejects the feature name (Android's Expat, which is secure by
        // default and never resolves external entities) is tolerated.
        setAdaptiveFeature(FEATURE_DISALLOW_DOCTYPE, true, "DOCTYPE declarations")
        setAdaptiveFeature(FEATURE_EXTERNAL_GENERAL, false, "external general entities")
        setAdaptiveFeature(FEATURE_EXTERNAL_PARAMETER, false, "external parameter entities")
        setAdaptiveFeature(FEATURE_LOAD_EXTERNAL_DTD, false, "external DTD loading")
        setAdaptiveFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true, "secure processing")
        setAdaptiveProperty(PROPERTY_ACCESS_EXTERNAL_DTD, "", "external DTD access")
        setAdaptiveProperty(PROPERTY_ACCESS_EXTERNAL_SCHEMA, "", "external schema access")
        runCatching { setExpandEntityReferences(false) }
    }

    private fun DocumentBuilderFactory.setAdaptiveFeature(feature: String, value: Boolean, label: String) {
        val applied = runCatching {
            setFeature(feature, value)
            getFeature(feature) == value
        }
        when {
            applied.getOrNull() == true -> Unit
            applied.isFailure -> Unit
            else -> throw IllegalStateException(
                "XML parser accepted but did not apply the $label hardening ($feature)",
            )
        }
    }

    private fun DocumentBuilderFactory.setAdaptiveProperty(property: String, value: String, label: String) {
        runCatching { setAttribute(property, value) }
    }
}
