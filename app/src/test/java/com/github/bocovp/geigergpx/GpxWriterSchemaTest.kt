package com.github.bocovp.geigergpx

import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory
import org.junit.Test

class GpxWriterSchemaTest {
    private val poi = PoiEntry("poi", 1_700_000_000_000L, 1.0, 2.0, 0.5, 5, 1.0, "Name & <description>")

    private fun validate(xml: String) {
        val factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
        val schema = factory.newSchema(javaClass.getResource("/gpx11.xsd"))
        schema.newValidator().validate(StreamSource(StringReader(xml)))
    }

    @Test
    fun embeddedTimestampedAndUntimestampedPoisConformToGpx11() {
        val text = StringWriter()
        text.buffered().use {
            GpxWriter.writeTrackXml(it, emptyList(), false, pois = listOf(poi, poi.copy(timestampMillis = 0)))
        }
        validate(text.toString())
    }

    @Test
    fun libraryTimestampedAndUntimestampedPoisConformToGpx11() {
        validate(GpxWriter.serializePoiEntries(listOf(poi, poi.copy(timestampMillis = 0))))
    }
}
