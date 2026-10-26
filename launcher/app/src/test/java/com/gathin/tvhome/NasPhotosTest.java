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
        assertTrue(NasPathSet.covers(hidden, "2024/a.jpg"));
        assertFalse(NasPathSet.covers(hidden, "2024/b.jpg"));
        assertTrue(NasPathSet.covers(hidden, "!證件照/x.jpg"));
        assertTrue(NasPathSet.covers(hidden, "2023/京都/深處/x.heic"));
        assertFalse(NasPathSet.covers(hidden, "2023/京都旅行/x.heic"));
        assertFalse(NasPathSet.covers(Collections.emptySet(), "2024/a.jpg"));
        assertEquals("2023/京都/", NasPathSet.folderOf("2023/京都/x.heic"));
        assertEquals("", NasPathSet.folderOf("x.heic"));
    }

    @Test
    public void shareAndPhotoPathsArePercentEncoded() {
        String share = "%E7%9B%B8%E7%89%87";
        assertEquals("https://192.168.1.50:5001/" + share + "/.tvphotos/index.txt.gz",
                NasClient.urlFor("https://192.168.1.50:5001/相片", ".tvphotos/index.txt.gz"));
        assertEquals("https://192.168.1.50:5001/" + share + "/phone%202024/a%23b.HEIC",
                NasClient.urlFor("https://192.168.1.50:5001/" + share, "phone 2024/a#b.HEIC"));
        assertEquals("https://nas/Photos/Family%20trips/100%25.jpg", NasClient.urlFor("https://nas/Photos", "Family trips/100%.jpg"));
    }

    @Test
    public void theDateComesFromTheNameOrTheNearestFolder() {
        org.junit.Assert.assertArrayEquals(new int[]{2024, 4, 28}, NasPhotos.dateOf("2024/IMG_20240428_171025.jpg"));
        org.junit.Assert.assertArrayEquals(new int[]{2024, 3, 5}, NasPhotos.dateOf("phone 2024/2024-03-05 19.53.43.jpg"));
        org.junit.Assert.assertArrayEquals(new int[]{2010, 10, 3}, NasPhotos.dateOf("Sync/2010/20101003 dinner/DSC03333.JPG"));
        assertEquals(null, NasPhotos.dateOf("Sync/2010/dinner/DSC03333.JPG"));
        assertEquals(null, NasPhotos.dateOf("a/120240428.jpg"));   // part of a longer number
        assertEquals(null, NasPhotos.dateOf("a/IMG_20241332.jpg"));   // no month 13
    }

    @Test
    public void onThisDayIsAnEarlierYearOnTodaysDate() {
        assertTrue(NasPhotos.onThisDay("2018/IMG_20181026_090000.jpg", 2026, 10, 26));
        assertFalse(NasPhotos.onThisDay("2026/IMG_20261026_090000.jpg", 2026, 10, 26));   // today itself
        assertFalse(NasPhotos.onThisDay("2018/IMG_20181025_090000.jpg", 2026, 10, 26));
        assertTrue(NasPhotos.onThisDay("Sync/20121026 trip/DSC1.JPG", 2026, 10, 26));
    }

    @Test
    public void captionIsTheFolderPath() {
        assertEquals("京都", NasPhotoFrame.caption("2023/京都/IMG_1.HEIC"));
        assertEquals("2024", NasPhotoFrame.caption("2024/IMG_1.HEIC"));
        assertEquals("", NasPhotoFrame.caption("IMG_1.HEIC"));
    }
}
