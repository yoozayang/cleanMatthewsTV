package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class NasPhotosTest {
    @Test
    public void formatSwitchesPickJpegAndHeicOnly() {
        assertTrue(NasPhotos.wantsType("2024/a.JPG", true, false));
        assertTrue(NasPhotos.wantsType("2024/a.jpeg", true, false));
        assertFalse(NasPhotos.wantsType("2024/a.HEIC", true, false));
        assertTrue(NasPhotos.wantsType("2024/a.HEIC", false, true));
        assertTrue(NasPhotos.wantsType("2024/a.heif", false, true));
        assertFalse(NasPhotos.wantsType("2024/a.jpg", false, true));
    }

    @Test
    public void rawAndVideoNeverPass() {
        for (String p : new String[]{"a.ARW", "a.dng", "a.RAF", "a.mov", "a.mp4", "a.png", "a.jpg.xmp"}) {
            assertFalse(p, NasPhotos.wantsType(p, true, true));
        }
    }

    @Test
    public void noFolderChosenMeansEveryFolder() {
        assertTrue(NasPhotos.inFolders("anything/at/all.jpg", Collections.emptySet()));
    }

    @Test
    public void aFolderHoldsItsSubfoldersButNotItsNamesakes() {
        Set<String> f = new HashSet<>(Collections.singletonList("2023"));
        assertTrue(NasPhotos.inFolders("2023/a.jpg", f));
        assertTrue(NasPhotos.inFolders("2023/京都/a.jpg", f));
        assertFalse(NasPhotos.inFolders("2023-backup/a.jpg", f));
        assertFalse(NasPhotos.inFolders("2024/a.jpg", f));
    }

    @Test
    public void secondLevelFoldersCanBePickedAlone() {
        Set<String> f = new HashSet<>(Collections.singletonList("phone 2024/旅行"));
        assertTrue(NasPhotos.inFolders("phone 2024/旅行/a.heic", f));
        assertFalse(NasPhotos.inFolders("phone 2024/其他/a.heic", f));
    }

    @Test
    public void hiddenPhotosAndFolders() {
        Set<String> hidden = new HashSet<>(java.util.Arrays.asList("2024/a.jpg", "!證件照/", "2023/京都/"));
        assertTrue(NasHidden.hides(hidden, "2024/a.jpg"));
        assertFalse(NasHidden.hides(hidden, "2024/b.jpg"));
        assertTrue(NasHidden.hides(hidden, "!證件照/x.jpg"));
        assertTrue(NasHidden.hides(hidden, "2023/京都/深處/x.heic"));
        assertFalse(NasHidden.hides(hidden, "2023/京都旅行/x.heic"));
        assertFalse(NasHidden.hides(Collections.emptySet(), "2024/a.jpg"));
        assertEquals("2023/京都/", NasHidden.folderOf("2023/京都/x.heic"));
        assertEquals("", NasHidden.folderOf("x.heic"));
    }

    @Test
    public void captionIsTheFolderPath() {
        assertEquals("2023 · 京都", NasPhotoFrame.caption("2023/京都/IMG_1.HEIC"));
        assertEquals("", NasPhotoFrame.caption("IMG_1.HEIC"));
    }
}
