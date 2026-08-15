package com.uma.workbench.imports

import com.uma.workbench.audit.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceKindClassifierTest {
    @Test fun classifiesApkAsArchiveForStreamingEntryIndexing() {
        assertEquals(SourceKind.ARCHIVE, SourceKindClassifier.classify("complete-game.APK"))
        assertEquals(SourceKind.ARCHIVE, SourceKindClassifier.classify("capture.tar"))
    }

    @Test fun classifiesNativeMetadataAndSessionInputs() {
        assertEquals(SourceKind.SO, SourceKindClassifier.classify("libil2cpp.so"))
        assertEquals(SourceKind.IL2CPP_METADATA, SourceKindClassifier.classify("global-metadata.dat"))
        assertEquals(SourceKind.SESSION, SourceKindClassifier.classify("session.jsonl"))
        assertEquals(SourceKind.SESSION, SourceKindClassifier.classify("protocol.ndjson"))
    }
}
