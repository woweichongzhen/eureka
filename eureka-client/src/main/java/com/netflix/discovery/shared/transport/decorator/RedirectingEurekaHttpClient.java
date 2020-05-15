/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.discovery.shared.transport.decorator;

import com.netflix.discovery.shared.dns.DnsService;
import com.netflix.discovery.shared.dns.DnsServiceImpl;
import com.netflix.discovery.shared.resolver.DefaultEndpoint;
import com.netflix.discovery.shared.resolver.EurekaEndpoint;
import com.netflix.discovery.shared.transport.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 寻找非 302 重定向的 Eureka-Server 的 EurekaHttpClient
 * <p>
 * {@link EurekaHttpClient} that follows redirect links, and executes the requests against
 * the finally resolved endpoint.
 * If registration and query requests must handled separately, two different instances shall be created.
 * <h3>Thread safety</h3>
 * Methods in this class may be called concurrently.
 *
 * @author Tomasz Bak
 */
public class RedirectingEurekaHttpClient extends EurekaHttpClientDecorator {

    private static final Logger logger = LoggerFactory.getLogger(RedirectingEurekaHttpClient.class);

    /**
     * 最大的重定向数
     */
    public static final int MAX_FOLLOWED_REDIRECTS = 10;

    /**
     * 重定向path匹配正则
     */
    private static final Pattern REDIRECT_PATH_REGEX = Pattern.compile("(.*/v2/)apps(/.*)?$");

    /**
     * server端点
     */
    private final EurekaEndpoint serviceEndpoint;

    /**
     * 传输client工厂
     */
    private final TransportClientFactory factory;

    /**
     * dns服务
     */
    private final DnsService dnsService;

    /**
     * http请求原子引用委托
     */
    private final AtomicReference<EurekaHttpClient> delegateRef = new AtomicReference<>();

    /**
     * The delegate client should pass through 3xx responses without further processing.
     */
    public RedirectingEurekaHttpClient(String serviceUrl, TransportClientFactory factory, DnsService dnsService) {
        this.serviceEndpoint = new DefaultEndpoint(serviceUrl);
        this.factory = factory;
        this.dnsService = dnsService;
    }

    @Override
    public void shutdown() {
        TransportUtils.shutdown(delegateRef.getAndSet(null));
    }

    @Override
    protected <R> EurekaHttpResponse<R> execute(RequestExecutor<R> requestExecutor) {
        // 获取http委托，若为空通过工厂和端点创建一个引用
        EurekaHttpClient currentEurekaClient = delegateRef.get();
        if (currentEurekaClient == null) {
            AtomicReference<EurekaHttpClient> currentEurekaClientRef =
                    new AtomicReference<>(factory.newClient(serviceEndpoint));
            try {
                // 未找到非 302 的 Eureka-Server
                EurekaHttpResponse<R> response = executeOnNewServer(requestExecutor, currentEurekaClientRef);
                // 关闭原有的委托 EurekaHttpClient ，并设置当前成功非 302 请求的 EurekaHttpClient
                // 关闭原有的 delegateRef ( 因为此处可能存在并发，多个线程都找到非 302 状态码返回的 Eureka-Server )
                // 并设置当前成功非 302 请求的 EurekaHttpClient 到 delegateRef
                TransportUtils.shutdown(delegateRef.getAndSet(currentEurekaClientRef.get()));
                return response;
            } catch (Exception e) {
                logger.error("Request execution error. endpoint={}", serviceEndpoint, e);
                TransportUtils.shutdown(currentEurekaClientRef.get());
                throw e;
            }
        } else {
            // 已经找到非 302 的 Eureka-Server
            try {
                return requestExecutor.execute(currentEurekaClient);
            } catch (Exception e) {
                logger.error("Request execution error. endpoint={}", serviceEndpoint, e);
                delegateRef.compareAndSet(currentEurekaClient, null);
                currentEurekaClient.shutdown();
                throw e;
            }
        }
    }

    /**
     * 静态方法获得创建本类的工厂
     *
     * @param delegateFactory 委托工厂
     * @return 传输client工厂
     */
    public static TransportClientFactory createFactory(final TransportClientFactory delegateFactory) {
        final DnsServiceImpl dnsService = new DnsServiceImpl();
        return new TransportClientFactory() {
            @Override
            public EurekaHttpClient newClient(EurekaEndpoint endpoint) {
                return new RedirectingEurekaHttpClient(endpoint.getServiceUrl(), delegateFactory, dnsService);
            }

            @Override
            public void shutdown() {
                delegateFactory.shutdown();
            }
        };
    }

    /**
     * 上一个为302，寻找新server执行
     *
     * @param requestExecutor      请求执行器
     * @param currentHttpClientRef http引用
     */
    private <R> EurekaHttpResponse<R> executeOnNewServer(RequestExecutor<R> requestExecutor,
                                                         AtomicReference<EurekaHttpClient> currentHttpClientRef) {
        URI targetUrl = null;
        // 小于最大重定向数就继续遍历
        for (int followRedirectCount = 0; followRedirectCount < MAX_FOLLOWED_REDIRECTS; followRedirectCount++) {
            // 执行server请求
            EurekaHttpResponse<R> httpResponse = requestExecutor.execute(currentHttpClientRef.get());
            // 非302返回
            if (httpResponse.getStatusCode() != 302) {
                if (followRedirectCount == 0) {
                    logger.debug("Pinning to endpoint {}", targetUrl);
                } else {
                    logger.info("Pinning to endpoint {}, after {} redirect(s)", targetUrl, followRedirectCount);
                }
                return httpResponse;
            }

            targetUrl = getRedirectBaseUri(httpResponse.getLocation());
            if (targetUrl == null) {
                throw new TransportException("Invalid redirect URL " + httpResponse.getLocation());
            }

            currentHttpClientRef.getAndSet(null).shutdown();
            currentHttpClientRef.set(factory.newClient(new DefaultEndpoint(targetUrl.toString())));
        }
        String message = "Follow redirect limit crossed for URI " + serviceEndpoint.getServiceUrl();
        logger.warn(message);
        throw new TransportException(message);
    }

    /**
     * 获取重定向基础uri
     */
    private URI getRedirectBaseUri(URI locationURI) {
        if (locationURI == null) {
            throw new TransportException("Missing Location header in the redirect reply");
        }
        // 正则匹配获取uri
        Matcher pathMatcher = REDIRECT_PATH_REGEX.matcher(locationURI.getPath());
        if (pathMatcher.matches()) {
            return UriBuilder.fromUri(locationURI)
                    .host(dnsService.resolveIp(locationURI.getHost()))
                    .replacePath(pathMatcher.group(1))
                    .replaceQuery(null)
                    .build();
        }
        logger.warn("Invalid redirect URL {}", locationURI);
        return null;
    }
}
