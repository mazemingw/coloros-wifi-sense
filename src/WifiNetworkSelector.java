import java.util.List;

/** Matches Android's primary Wi-Fi connection to its ConnectivityManager Network. */
final class WifiNetworkSelector {
    static final class Candidate<T> {
        final T network;
        final String bssid;
        final Boolean primary;

        Candidate(T network, String bssid, Boolean primary) {
            this.network = network;
            this.bssid = validBssid(bssid) ? bssid : null;
            this.primary = primary;
        }
    }

    static boolean validBssid(String value) {
        return value != null && !value.isEmpty() && !"02:00:00:00:00:00".equals(value);
    }

    static <T> T select(List<Candidate<T>> candidates, String expectedBssid) {
        Candidate<T> primary = null;
        for (Candidate<T> candidate : candidates) {
            if (Boolean.TRUE.equals(candidate.primary)) {
                if (primary != null) return null;
                primary = candidate;
            }
        }
        if (primary != null) {
            if (validBssid(expectedBssid) && primary.bssid != null
                    && !expectedBssid.equalsIgnoreCase(primary.bssid)) return null;
            return primary.network;
        }

        Candidate<T> matching = null;
        if (validBssid(expectedBssid)) {
            for (Candidate<T> candidate : candidates) {
                if (!Boolean.FALSE.equals(candidate.primary)
                        && expectedBssid.equalsIgnoreCase(candidate.bssid)) {
                    if (matching != null) return null;
                    matching = candidate;
                }
            }
        }
        if (matching != null) return matching.network;
        if (candidates.size() == 1) {
            Candidate<T> candidate = candidates.get(0);
            if (!Boolean.FALSE.equals(candidate.primary)
                    && (!validBssid(expectedBssid) || candidate.bssid == null)) return candidate.network;
        }
        return null;
    }
}
