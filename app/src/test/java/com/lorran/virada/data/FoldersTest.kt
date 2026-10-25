package com.lorran.virada.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldersTest {
    @Test fun normalizesPaths() {
        assertEquals("Music/Funk", normalizeFolder("Music/Funk/", null))
        assertEquals("Download", normalizeFolder(null, "/storage/emulated/0/Download/musica.mp3"))
        assertEquals("Music/DJ", normalizeFolder(null, "/storage/1A2B-3C4D/Music/DJ/x.mp3"))
        assertEquals("", normalizeFolder(null, null))
    }

    @Test fun includesSubfoldersOnly() {
        val sel = setOf("Music/Funk")
        assertTrue(inFolders("Music/Funk", sel))
        assertTrue(inFolders("Music/Funk/2024", sel))
        assertFalse(inFolders("Music/Funk2", sel))
        assertFalse(inFolders("Music", sel))
        assertTrue(inFolders("WhatsApp/Audio", emptySet()))
    }
}
