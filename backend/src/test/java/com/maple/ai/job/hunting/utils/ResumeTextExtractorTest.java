package com.maple.ai.job.hunting.utils;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeTextExtractorTest {

    @Test
    void malformedDocxXmlWithRepeatedOpeningTagsIsBounded() throws Exception {
        byte[] docx = docxWithDocumentXml("<".repeat(50_000));

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            String extracted = ResumeTextExtractor.extract(docx);
            assertTrue(extracted.isEmpty() || extracted.length() <= 50_000);
        });
    }

    private byte[] docxWithDocumentXml(String xml) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
