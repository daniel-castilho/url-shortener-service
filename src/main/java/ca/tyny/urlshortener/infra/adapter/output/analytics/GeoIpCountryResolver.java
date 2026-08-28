package ca.tyny.urlshortener.infra.adapter.output.analytics;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CountryResponse;
import com.maxmind.geoip2.record.Country;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.InetAddress;
import java.util.Locale;

/**
 * Best-effort ISO-3166 country code lookup (GeoIP2) for click events.
 *
 * <p>Purely a privacy/analytics enrichment: {@link #countryForIp(String)}
 * never blocks on hard failure and always returns {@code null} when disabled,
 * misconfigured, missing the database file, seeing a private/internal IP, or
 * on any lookup error. Slow/failed lookups must never slow the batch.
 */
public class GeoIpCountryResolver {

    private static final Logger log = LoggerFactory.getLogger(GeoIpCountryResolver.class);

    private final boolean enabled;
    private final DatabaseReader reader;

    public GeoIpCountryResolver(boolean enabled, DatabaseReader reader) {
        this.enabled = enabled;
        this.reader = reader;
    }

    /**
     * A resolver that never returns a country (geo disabled or unavailable).
     */
    public static GeoIpCountryResolver disabled() {
        return new GeoIpCountryResolver(false, null);
    }

    /**
     * Loads the MaxMind country database from disk. Fails open: any load error
     * yields a disabled resolver so analytics never breaks startup.
     */
    public static GeoIpCountryResolver fromFile(String maxmindDbPath) {
        if (maxmindDbPath == null || maxmindDbPath.isBlank()) {
            log.warn("Geo enrichment enabled but app.analytics.geo.maxmind-db-path is empty — disabling");
            return disabled();
        }
        try {
            return new GeoIpCountryResolver(true,
                    new DatabaseReader.Builder(new File(maxmindDbPath)).build());
        } catch (Exception e) {
            log.warn("Could not load MaxMind database from '{}' — geo enrichment disabled: {}",
                    maxmindDbPath, e.getMessage());
            return disabled();
        }
    }

    /**
     * @return uppercase ISO-3166 alpha-2 code or {@code null}
     */
    public String countryForIp(String ip) {
        if (!enabled || reader == null || ip == null || ip.isBlank()) {
            return null;
        }
        try {
            InetAddress address = InetAddress.getByName(ip);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
                return null;
            }
            CountryResponse response = reader.country(address);
            Country country = response.getCountry();
            String code = country == null ? null : country.getIsoCode();
            return code == null || code.isBlank() ? null : code.toUpperCase(Locale.ROOT);
        } catch (Exception e) {
            log.debug("Geo lookup failed for ip {}: {}", ip, e.getMessage());
            return null;
        }
    }
}