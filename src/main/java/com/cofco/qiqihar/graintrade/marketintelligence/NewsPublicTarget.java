package com.cofco.qiqihar.graintrade.marketintelligence;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Set;

/** Validated DNS snapshot, NOT a fetch permission. Transport must connect to these exact IPs. */
final class NewsPublicTarget {
    interface Resolver { InetAddress[] resolve(String host) throws IOException; }
    record Target(URI uri, List<InetAddress> addresses) {
        Target { addresses = List.copyOf(addresses); }
    }
    static Target resolve(URI uri, Resolver resolver) throws IOException {
        try {
            var normalized = normalize(uri);
            var addresses = resolver.resolve(normalized.getHost());
            if (addresses == null || addresses.length == 0 || addresses.length > 32) throw unsafe();
            for (var address : addresses) if (address == null || !publicAddress(address)) throw unsafe();
            return new Target(normalized, List.of(addresses));
        } catch (IOException | RuntimeException failure) {
            throw unsafe();
        }
    }
    static Target redirect(URI current, String location, Set<URI> visited, Resolver resolver) throws IOException {
        try {
            if (location == null || location.isBlank() || location.length() > 2048) throw unsafe();
            var target = normalize(normalize(current).resolve(location));
            if (target.equals(normalize(current))) throw unsafe();
            for (var previous : visited) if (target.equals(normalize(previous))) throw unsafe();
            return resolve(target, resolver);
        } catch (IOException | RuntimeException failure) {
            throw unsafe();
        }
    }

    private static URI normalize(URI uri) throws IOException {
        if (uri == null || uri.toString().length() > 2048) throw unsafe();
        var normalized = NewsSearchResults.candidateUri(uri.toString());
        if (normalized == null) throw unsafe();
        return normalized;
    }

    // Conservative deny policy based on IANA special-purpose registries (2026-09-28).
    // It deliberately rejects entire protocol/transition blocks even when individual exceptions are global.
    private static boolean publicAddress(InetAddress address) {
        var bytes = address.getAddress();
        int a = bytes[0] & 255, b = bytes[1] & 255, c = bytes[2] & 255;
        if (bytes.length == 4) {
            return !(a == 0 || a == 10 || a == 127 || a >= 224
                || a == 100 && b >= 64 && b <= 127
                || a == 169 && b == 254
                || a == 172 && b >= 16 && b <= 31
                || a == 192 && (b == 168 || b == 0 && (c == 0 || c == 2) || b == 88 && c == 99)
                || a == 198 && (b == 18 || b == 19 || b == 51 && c == 100)
                || a == 203 && b == 0 && c == 113);
        }
        if (bytes.length != 16 || (a & 224) != 32) return false; // Global unicast 2000::/3 only.
        int d = bytes[3] & 255;
        return !(a == 32 && b == 1 && c < 2 // 2001::/23 special protocols, includes Teredo
                || a == 32 && b == 1 && c == 13 && d == 184 // documentation
                || a == 32 && b == 2 // 6to4
                || a == 63 && b == 255 && (c & 240) == 0); // 3fff::/20 documentation
    }

    private static IOException unsafe() { return new IOException("Unsafe news target"); }
}
