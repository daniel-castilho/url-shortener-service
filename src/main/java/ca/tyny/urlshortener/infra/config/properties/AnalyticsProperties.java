package ca.tyny.urlshortener.infra.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.analytics")
public class AnalyticsProperties {

    private Geo geo = new Geo();

    /** Maintain a Redis HyperLogLog per (shortCode, day) to report unique clicks. */
    private boolean uniqueEnabled = true;

    public Geo getGeo() {
        return geo;
    }

    public void setGeo(Geo geo) {
        this.geo = geo;
    }

    public boolean isUniqueEnabled() {
        return uniqueEnabled;
    }

    public void setUniqueEnabled(boolean uniqueEnabled) {
        this.uniqueEnabled = uniqueEnabled;
    }

    public static class Geo {

        /** Master switch; country enrichment is off unless explicitly enabled. */
        private boolean enabled = false;

        /** Absolute path to the GeoLite2-Country.mmdb database file. */
        private String maxmindDbPath = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getMaxmindDbPath() {
            return maxmindDbPath;
        }

        public void setMaxmindDbPath(String maxmindDbPath) {
            this.maxmindDbPath = maxmindDbPath;
        }
    }
}