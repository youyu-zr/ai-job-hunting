package com.maple.ai.job.hunting.common.ai;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;

public final class ModelEndpointPolicy {

    private static final int MAX_BASE_URL_LENGTH = 2048;

    private ModelEndpointPolicy() {
    }

    public static URI validateBaseUrl(String value, boolean allowPrivateAddresses, boolean allowInsecureHttp) {
        if (value == null || value.isBlank() || value.length() > MAX_BASE_URL_LENGTH) {
            throw new IllegalArgumentException("模型服务地址无效");
        }

        URI uri;
        try {
            uri = new URI(value.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("模型服务地址无效", e);
        }

        String scheme = uri.getScheme();
        if (!uri.isAbsolute() || scheme == null || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getPort() > 65535
                || !(scheme.equalsIgnoreCase("https")
                || (allowInsecureHttp && scheme.equalsIgnoreCase("http")))) {
            throw new IllegalArgumentException("模型服务地址必须是有效的 HTTPS 地址");
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!allowPrivateAddresses) {
            if (host.equals("localhost") || host.endsWith(".localhost")
                    || host.endsWith(".local") || host.endsWith(".internal")) {
                throw new IllegalArgumentException("模型服务地址不能指向本机或内网域名");
            }
            validateIpLiteral(host);
        }
        return uri;
    }

    public static void validateCompletionPath(String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            URI uri = new URI(value.trim());
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPath() == null
                    || !uri.getPath().startsWith("/") || uri.getPath().startsWith("//")
                    || uri.getPath().contains("\\")) {
                throw new IllegalArgumentException("模型接口路径无效");
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("模型接口路径无效", e);
        }
    }

    public static DnsResolver publicAddressResolver(boolean allowPrivateAddresses) {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                InetAddress[] addresses = SystemDefaultDnsResolver.INSTANCE.resolve(host);
                if (addresses.length == 0 || (!allowPrivateAddresses
                        && Arrays.stream(addresses).anyMatch(address -> !isPublicAddress(address)))) {
                    throw new UnknownHostException("模型服务地址解析到非公网 IP");
                }
                return addresses;
            }

            @Override
            public String resolveCanonicalHostname(String host) throws UnknownHostException {
                return SystemDefaultDnsResolver.INSTANCE.resolveCanonicalHostname(host);
            }
        };
    }

    public static boolean isPublicAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }

        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            return isPublicIpv4(bytes, 0);
        }
        if (!(address instanceof Inet6Address) || !isGlobalIpv6(bytes)) {
            return false;
        }

        if (isIpv4Mapped(bytes)) {
            return isPublicIpv4(bytes, 12);
        }
        return true;
    }

    private static void validateIpLiteral(String host) {
        if (!(host.matches("[0-9.]+") || host.contains(":"))) {
            return;
        }
        try {
            if (!isPublicAddress(InetAddress.getByName(host))) {
                throw new IllegalArgumentException("模型服务地址不能指向本机或内网 IP");
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("模型服务 IP 地址无效", e);
        }
    }

    private static boolean isPublicIpv4(byte[] bytes, int offset) {
        int first = Byte.toUnsignedInt(bytes[offset]);
        int second = Byte.toUnsignedInt(bytes[offset + 1]);
        int third = Byte.toUnsignedInt(bytes[offset + 2]);

        if (first == 0 || first == 10 || first == 127 || first >= 224
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168)
                || (first == 198 && (second == 18 || second == 19))
                || (first == 198 && second == 51 && third == 100)
                || (first == 203 && second == 0 && third == 113)
                || (first == 192 && second == 0 && third == 0)
                || (first == 192 && second == 0 && third == 2)
                || (first == 192 && second == 88 && third == 99)) {
            return false;
        }
        return true;
    }

    private static boolean isGlobalIpv6(byte[] bytes) {
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        int third = Byte.toUnsignedInt(bytes[2]);
        int fourth = Byte.toUnsignedInt(bytes[3]);

        boolean globalUnicast = (first & 0xE0) == 0x20;
        boolean documentation = first == 0x20 && second == 0x01 && third == 0x0D && fourth == 0xB8;
        boolean specialPurpose = first == 0x20 && second == 0x01
                && (third & 0xFE) == 0x00;
        boolean sixToFour = first == 0x20 && second == 0x02;
        boolean documentationV2 = first == 0x3F && (second & 0xF0) == 0xF0;
        return globalUnicast && !documentation && !specialPurpose && !sixToFour && !documentationV2;
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
    }
}
