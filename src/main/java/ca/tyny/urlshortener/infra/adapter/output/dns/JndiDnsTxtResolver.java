package ca.tyny.urlshortener.infra.adapter.output.dns;

import ca.tyny.urlshortener.infra.config.properties.DomainProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

/**
 * Default {@link DnsTxtResolver} backed by the JDK JNDI DNS provider
 * ({@code jdk.naming.dns}) — no external dependency.
 *
 * <p>Quotes around TXT values are stripped (JNDI returns them quoted). Any lookup failure
 * (name missing, timeout, NXDOMAIN) is treated as "no record", which the verification
 * service reads as a failed check.
 */
@Component
public class JndiDnsTxtResolver implements DnsTxtResolver {

    private static final Logger log = LoggerFactory.getLogger(JndiDnsTxtResolver.class);

    private static final String DNS_PROVIDER = "com.sun.jndi.dns.DnsContextFactory";
    private static final String PROVIDER_URL = "dns:";
    private static final String TIMEOUT_INITIAL = "com.sun.jndi.dns.timeout.initial";
    private static final String TIMEOUT_RETRIES = "com.sun.jndi.dns.timeout.retries";

    private final int timeoutMs;

    public JndiDnsTxtResolver(DomainProperties properties) {
        this.timeoutMs = properties.dnsVerifyTimeoutMs();
    }

    @Override
    public List<String> resolveTxt(String host) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, DNS_PROVIDER);
        env.put(Context.PROVIDER_URL, PROVIDER_URL);
        env.put(TIMEOUT_INITIAL, Integer.toString(timeoutMs));
        env.put(TIMEOUT_RETRIES, "1");

        try {
            InitialDirContext ctx = new InitialDirContext(env);
            try {
                Attributes attrs = ctx.getAttributes(host, new String[]{"TXT"});
                Attribute txt = attrs.get("TXT");
                if (txt == null) {
                    return new ArrayList<>();
                }
                List<String> values = new ArrayList<>(txt.size());
                NamingEnumeration<?> all = txt.getAll();
                while (all.hasMore()) {
                    String value = String.valueOf(all.next()).replace("\"", "");
                    values.add(value);
                }
                return values;
            } finally {
                ctx.close();
            }
        } catch (NamingException e) {
            log.debug("DNS TXT lookup failed for {}: {}", host, e.getMessage());
            return new ArrayList<>();
        }
    }
}