package il.david.govmap.dark;

import java.net.URI;
import java.net.URISyntaxException;

final class GovMapLinks {
    private GovMapLinks() {}

    static String trustedUrl(String url) {
        if (url == null) return null;
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return null;
            String host = uri.getHost();
            if (!"www.govmap.gov.il".equalsIgnoreCase(host) && !"govmap.gov.il".equalsIgnoreCase(host)) return null;
            String authority = uri.getRawAuthority();
            if (!host.equalsIgnoreCase(authority) && !(host + ":443").equalsIgnoreCase(authority)) return null;
            // GovMap uses the original query and fragment to restore the shared view.
            return url;
        } catch (URISyntaxException ignored) {
            return null;
        }
    }
}
