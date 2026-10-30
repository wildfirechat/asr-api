package cn.wildfirechat.asr.utilis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 音频下载地址校验，防止 SSRF / 任意本地文件读取。
 *
 * <p>客户端调用 /api/recognize 时传入的 url 完全由客户端控制。如果直接
 * {@code new URL(url).openStream()}，攻击者可以传 {@code file:///etc/passwd} 读取本机文件，
 * 也可以传内网地址访问只有服务器能访问的管理接口、云厂商元数据地址（169.254.169.254）等。
 *
 * <p>策略：
 * <ul>
 *   <li>只允许 http/https，禁止 file/jar/ftp 等协议；</li>
 *   <li>默认禁止回环、内网、链路本地、CGNAT 等地址（{@code asr.download.block_private_networks=true}）；</li>
 *   <li>私有化部署如果把音频放在内网对象存储，把对应主机配到
 *       {@code asr.download.allowed_hosts}（逗号分隔，支持 host 或 host:port）；</li>
 *   <li>云厂商元数据地址始终禁止，即使配置了 allowed_hosts。</li>
 * </ul>
 */
@Component
public class AudioUrlValidator {
    private static final Logger LOG = LoggerFactory.getLogger(AudioUrlValidator.class);

    /** 云厂商元数据主机名，始终拒绝。 */
    private static final Set<String> BLOCKED_HOSTS = new HashSet<>(Arrays.asList(
            "metadata",
            "metadata.google.internal",
            "metadata.tencentyun.com",
            "instance-data"
    ));

    @Value("${asr.download.block_private_networks:true}")
    private boolean blockPrivateNetworks;

    @Value("${asr.download.allowed_hosts:}")
    private String allowedHostsConfig;

    private Set<String> allowedHosts = new HashSet<>();

    @PostConstruct
    void init() {
        allowedHosts = new HashSet<>();
        if (StringUtils.hasText(allowedHostsConfig)) {
            for (String host : allowedHostsConfig.split(",")) {
                host = host.trim().toLowerCase(Locale.ROOT);
                if (!host.isEmpty()) {
                    allowedHosts.add(host);
                }
            }
        }
        LOG.info("audio url validator initialized, blockPrivateNetworks={}, allowedHosts={}",
                blockPrivateNetworks, allowedHosts);
    }

    /**
     * 校验并返回规范化后的 URL。协议、主机或解析结果不合法时抛出 IOException。
     */
    public URL validate(URL url) throws IOException {
        if (url == null) {
            throw new IOException("audio url is null");
        }
        String protocol = url.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            throw new IOException("unsupported url protocol: " + protocol);
        }

        String host = url.getHost();
        if (!StringUtils.hasText(host)) {
            throw new IOException("audio url has no host");
        }
        host = normalizeHost(host);
        if (BLOCKED_HOSTS.contains(host) || host.endsWith(".metadata.google.internal")) {
            throw new IOException("audio url host is not allowed: " + host);
        }

        // 明确配置的主机直接放行（https 证书校验仍由 JVM 完成），用于内网对象存储。
        // 但链路本地/云元数据地址即使配进白名单也拒绝。
        if (allowedHosts.contains(host) || allowedHosts.contains(host + ":" + effectivePort(url))) {
            try {
                for (InetAddress address : InetAddress.getAllByName(host)) {
                    if (address.isLinkLocalAddress() || isCloudMetadataAddress(address)) {
                        throw new IOException("audio url resolves to a blocked address: " + address.getHostAddress());
                    }
                }
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("failed to resolve audio url host: " + host, e);
            }
            return url;
        }

        if (blockPrivateNetworks) {
            InetAddress[] addresses;
            try {
                addresses = InetAddress.getAllByName(host);
            } catch (Exception e) {
                throw new IOException("failed to resolve audio url host: " + host, e);
            }
            for (InetAddress address : addresses) {
                if (isBlockedAddress(address)) {
                    throw new IOException("audio url resolves to a blocked address: " + address.getHostAddress());
                }
            }
        }
        return url;
    }

    /**
     * 仅校验协议和主机格式，不做 DNS 解析。用于重定向目标的第一道检查。
     */
    public static boolean isHttpUrl(URL url) {
        if (url == null) {
            return false;
        }
        String protocol = url.getProtocol();
        return ("http".equalsIgnoreCase(protocol) || "https".equalsIgnoreCase(protocol))
                && StringUtils.hasText(url.getHost());
    }

    private static String normalizeHost(String host) {
        host = host.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        // URL 里的国际化域名会被保留为 Unicode，这里统一转成 ASCII，避免绕过黑名单。
        try {
            String ascii = new URI(null, null, host, -1, null, null, null).getHost();
            if (StringUtils.hasText(ascii)) {
                ascii = ascii.toLowerCase(Locale.ROOT);
                if (ascii.startsWith("[") && ascii.endsWith("]")) {
                    ascii = ascii.substring(1, ascii.length() - 1);
                }
                return ascii;
            }
        } catch (URISyntaxException ignored) {
            // 保持原值，后续解析失败会拒绝
        }
        return host;
    }

    private static boolean isCloudMetadataAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xff;
            int b1 = bytes[1] & 0xff;
            int b2 = bytes[2] & 0xff;
            int b3 = bytes[3] & 0xff;
            // 100.100.100.200（阿里云）以及 169.254.169.254（AWS/GCP/Azure/腾讯云）
            return (b0 == 100 && b1 == 100 && b2 == 100 && b3 == 200)
                    || (b0 == 169 && b1 == 254 && b2 == 169 && b3 == 254);
        }
        return false;
    }

    private static int effectivePort(URL url) {
        if (url.getPort() != -1) {
            return url.getPort();
        }
        return "https".equalsIgnoreCase(url.getProtocol()) ? 443 : 80;
    }

    /**
     * 判断地址是否属于不应从公网服务访问的范围。
     */
    public static boolean isBlockedAddress(InetAddress address) {
        if (address == null) {
            return true;
        }
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xff;
            int b1 = bytes[1] & 0xff;
            // 0.0.0.0/8
            if (b0 == 0) {
                return true;
            }
            // 100.64.0.0/10 CGNAT（含部分云厂商元数据 100.100.100.200）
            if (b0 == 100 && b1 >= 64 && b1 <= 127) {
                return true;
            }
            // 198.18.0.0/15 基准测试网段
            if (b0 == 198 && (b1 == 18 || b1 == 19)) {
                return true;
            }
            // 240.0.0.0/4 保留
            if (b0 >= 240) {
                return true;
            }
        } else if (bytes.length == 16) {
            // fc00::/7 唯一本地地址
            if ((bytes[0] & 0xfe) == 0xfc) {
                return true;
            }
            // IPv4 映射地址 ::ffff:a.b.c.d，按 IPv4 规则再判一次
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (bytes[i] != 0) {
                    mapped = false;
                    break;
                }
            }
            if (mapped && (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff) {
                byte[] v4 = new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
                try {
                    return isBlockedAddress(InetAddress.getByAddress(v4));
                } catch (Exception e) {
                    return true;
                }
            }
        }
        return false;
    }
}
