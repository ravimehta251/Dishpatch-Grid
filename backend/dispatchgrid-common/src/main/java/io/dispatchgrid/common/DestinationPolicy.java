package io.dispatchgrid.common;

import java.net.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Applied at registration AND by the HTTP connection manager's DNS resolver at connect time. */
@Component
public class DestinationPolicy {
  private final Set<String> developmentHosts;

  public DestinationPolicy(@Value("${dispatchgrid.allow-private-destinations:}") String allowlist) {
    developmentHosts = new HashSet<>();
    for (String host : allowlist.split(","))
      if (!host.isBlank()) developmentHosts.add(host.trim().toLowerCase(Locale.ROOT));
  }

  public URI validate(String url) {
    URI uri = validateStructure(url);
    try {
      resolve(uri.getHost());
      return uri;
    } catch (UnknownHostException e) {
      throw new ApiException(400, "Destination must resolve to a safe public address");
    }
  }

  /**
   * A transient resolver failure during an attempt must consume the retry policy, not terminate it.
   */
  public URI validateForDelivery(String url) throws UnknownHostException {
    URI uri = validateStructure(url);
    try {
      resolve(uri.getHost());
      return uri;
    } catch (UnsafeDestinationException e) {
      throw new ApiException(400, "Destination address prohibited");
    }
  }

  private URI validateStructure(String url) {
    try {
      if (url == null) throw new IllegalArgumentException();
      URI uri = new URI(url);
      String host = uri.getHost();
      if (host == null
          || uri.getUserInfo() != null
          || uri.getFragment() != null
          || url.length() > 2048) throw new IllegalArgumentException();
      boolean development = developmentHosts.contains(host.toLowerCase(Locale.ROOT));
      if (!"https".equalsIgnoreCase(uri.getScheme())
          && !(development && "http".equalsIgnoreCase(uri.getScheme())))
        throw new IllegalArgumentException();
      if (!development && uri.getPort() != -1 && uri.getPort() != 443)
        throw new IllegalArgumentException();
      return uri;
    } catch (URISyntaxException | IllegalArgumentException e) {
      throw new ApiException(400, "Destination must be a public HTTPS URL with a safe DNS address");
    }
  }

  public InetAddress[] resolve(String host) throws UnknownHostException {
    InetAddress[] addresses = InetAddress.getAllByName(host);
    boolean development = developmentHosts.contains(host.toLowerCase(Locale.ROOT));
    if (addresses.length == 0) throw new UnknownHostException("Empty destination DNS response");
    for (InetAddress address : addresses) {
      // Metadata is forbidden even when development host names are allowlisted.
      if (address.isLinkLocalAddress()
          || address.isAnyLocalAddress()
          || address.isMulticastAddress()
          || (!development && !isPublic(address))) throw new UnsafeDestinationException();
    }
    return addresses;
  }

  private static class UnsafeDestinationException extends UnknownHostException {
    UnsafeDestinationException() {
      super("Destination address prohibited");
    }
  }

  public static boolean isPublic(InetAddress address) {
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) return false;
    byte[] b = address.getAddress();
    int a = b[0] & 255, second = b[1] & 255;
    if (b.length == 4) {
      return !(a == 0
          || a == 10
          || a == 127
          || a >= 224
          || (a == 100 && second >= 64 && second <= 127)
          || (a == 169 && second == 254)
          || (a == 172 && second >= 16 && second <= 31)
          || (a == 192 && second == 168)
          || (a == 192 && second == 0)
          || (a == 192 && second == 88 && (b[2] & 255) == 99)
          || (a == 198 && (second == 18 || second == 19 || (second == 51 && (b[2] & 255) == 100)))
          || (a == 203 && second == 0 && (b[2] & 255) == 113));
    }
    // Restrict IPv6 to global-unicast 2000::/3, excluding documentation and transition prefixes.
    return (a & 0xe0) == 0x20
        && !(a == 0x20
            && second == 1
            && ((b[2] & 255) < 2 || ((b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8)))
        && !(a == 0x20 && second == 2);
  }
}
