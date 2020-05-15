package com.netflix.discovery.endpoint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.*;

/**
 * @author Tomasz Bak
 */
public final class DnsResolver {

    private static final Logger logger = LoggerFactory.getLogger(DnsResolver.class);

    private static final String DNS_PROVIDER_URL = "dns:";
    private static final String DNS_NAMING_FACTORY = "com.sun.jndi.dns.DnsContextFactory";
    private static final String JAVA_NAMING_FACTORY_INITIAL = "java.naming.factory.initial";
    private static final String JAVA_NAMING_PROVIDER_URL = "java.naming.provider.url";

    /**
     * A类型NDS
     */
    private static final String A_RECORD_TYPE = "A";
    /**
     * CNAME类型DNS
     */
    private static final String CNAME_RECORD_TYPE = "CNAME";
    /**
     * txt类型NDS
     */
    private static final String TXT_RECORD_TYPE = "TXT";

    private DnsResolver() {
    }

    /**
     * 获取java DNS JNDI上下文提供者
     * <p>
     * Load up the DNS JNDI context provider.
     */
    public static DirContext getDirContext() {
        Hashtable<String, String> env = new Hashtable<String, String>();
        env.put(JAVA_NAMING_FACTORY_INITIAL, DNS_NAMING_FACTORY);
        env.put(JAVA_NAMING_PROVIDER_URL, DNS_PROVIDER_URL);

        try {
            return new InitialDirContext(env);
        } catch (Throwable e) {
            throw new RuntimeException("Cannot get dir context for some reason", e);
        }
    }

    /**
     * 解析原始hostname， 获取 A记录或CNAME
     * Resolve host name to the bottom A-Record or the latest available CNAME
     *
     * @return resolved host name
     */
    public static String resolve(String originalHost) {
        String currentHost = originalHost;
        if (isLocalOrIp(currentHost)) {
            return originalHost;
        }
        try {
            String targetHost = null;
            do {
                Attributes attrs = getDirContext().getAttributes(currentHost, new String[]{A_RECORD_TYPE,
                        CNAME_RECORD_TYPE});
                Attribute attr = attrs.get(A_RECORD_TYPE);
                if (attr != null) {
                    targetHost = attr.get().toString();
                }
                attr = attrs.get(CNAME_RECORD_TYPE);
                if (attr != null) {
                    currentHost = attr.get().toString();
                } else {
                    targetHost = currentHost;
                }

            } while (targetHost == null);
            return targetHost;
        } catch (NamingException e) {
            logger.warn("Cannot resolve eureka server address {}; returning original value {}", currentHost,
                    originalHost, e);
            return originalHost;
        }
    }

    /**
     * Look into A-record at a specific DNS address.
     *
     * @return resolved IP addresses or null if no A-record was present
     */
    @Nullable
    public static List<String> resolveARecord(String rootDomainName) {
        if (isLocalOrIp(rootDomainName)) {
            return null;
        }
        try {
            Attributes attrs = getDirContext().getAttributes(rootDomainName, new String[]{A_RECORD_TYPE,
                    CNAME_RECORD_TYPE});
            Attribute aRecord = attrs.get(A_RECORD_TYPE);
            Attribute cRecord = attrs.get(CNAME_RECORD_TYPE);
            if (aRecord != null && cRecord == null) {
                List<String> result = new ArrayList<>();
                NamingEnumeration<String> entries = (NamingEnumeration<String>) aRecord.getAll();
                while (entries.hasMore()) {
                    result.add(entries.next());
                }
                return result;
            }
        } catch (Exception e) {
            logger.warn("Cannot load A-record for eureka server address {}", rootDomainName, e);
            return null;
        }
        return null;
    }

    /**
     * 是否是回环ip
     *
     * @param currentHost 主机名
     * @return true是，fale不是
     */
    private static boolean isLocalOrIp(String currentHost) {
        if ("localhost".equals(currentHost)) {
            return true;
        }
        if ("127.0.0.1".equals(currentHost)) {
            return true;
        }
        return false;
    }

    /**
     * 查找dns名称在JNDI上下文中
     * <p>
     * Looks up the DNS name provided in the JNDI context.
     */
    public static Set<String> getCNamesFromTxtRecord(String discoveryDnsName) throws NamingException {
        // 获取TXT类型的dns记录属性
        Attributes attrs = getDirContext().getAttributes(discoveryDnsName, new String[]{TXT_RECORD_TYPE});
        Attribute attr = attrs.get(TXT_RECORD_TYPE);
        String txtRecord = null;
        if (attr != null) {
            txtRecord = attr.get().toString();

            /*
             * compatible splited txt record of "host1 host2 host3" but not "host1" "host2" "host3".
             * some dns service provider support txt value only format "host1 host2 host3"
             */
            // 兼容的 "host1 host2 host3" 的拆分txt记录，
            // 但不兼容 "host1" "host2" "host3"
            // 一些DNS服务提供商仅支持txt值格式 "host1 host2 host3"
            if (txtRecord.startsWith("\"") && txtRecord.endsWith("\"")) {
                txtRecord = txtRecord.substring(1, txtRecord.length() - 1);
            }
        }

        // 不存在返回空的
        Set<String> cnamesSet = new TreeSet<String>();
        if (txtRecord == null || txtRecord.trim().isEmpty()) {
            return cnamesSet;
        }

        // 使用空格分隔
        String[] cnames = txtRecord.split(" ");
        Collections.addAll(cnamesSet, cnames);
        return cnamesSet;
    }
}
