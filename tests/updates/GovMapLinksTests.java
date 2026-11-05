package il.david.govmap.dark;

public final class GovMapLinksTests {
    public static void main(String[] args) {
        accepts("https://www.govmap.gov.il/?c=100000.5,200000.25&z=11&lay=23&b=0");
        accepts("https://govmap.gov.il:443/?b=1&lay=23%2C24#map");
        rejects(null);
        rejects("http://www.govmap.gov.il/");
        rejects("javascript:alert(1)");
        rejects("https://www.govmap.gov.il.example.org/");
        rejects("https://tiles.govmap.gov.il/");
        rejects("https://www.govmap.gov.il@www.govmap.gov.il/");
        rejects("https://example.org\\@www.govmap.gov.il/");
        rejects("https://www.govmap.gov.il:444/");
    }

    private static void accepts(String url) {
        if (!url.equals(GovMapLinks.trustedUrl(url))) throw new AssertionError("Link changed or rejected: " + url);
    }

    private static void rejects(String url) {
        if (GovMapLinks.trustedUrl(url) != null) throw new AssertionError("Unexpectedly accepted: " + url);
    }
}
