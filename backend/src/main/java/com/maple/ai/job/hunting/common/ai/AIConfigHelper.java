package com.maple.ai.job.hunting.common.ai;

import com.maple.ai.job.hunting.config.AppBizConfig;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Resource;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.function.Function;

/**
 * aiConfig 帮助程序
 *
 * @author gaoping
 * @since 2025/02/11
 */
@Component
public class AIConfigHelper {

    static final int DEFAULT_TIMEOUT_SECONDS = 60;
    static final int MAX_TIMEOUT_SECONDS = 120;
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    @Resource
    private AppBizConfig appBizConfig;

    @Value("${ai.custom-api.allow-private-addresses:false}")
    private boolean allowPrivateModelAddresses;

    @Value("${ai.custom-api.allow-insecure-http:false}")
    private boolean allowInsecureModelHttp;


    @Nonnull
    public RestClient.Builder buildProxyRestClient() {
        RequestConfig config = RequestConfig.custom()
                .setResponseTimeout(Timeout.ofSeconds(DEFAULT_TIMEOUT_SECONDS))
                .build();
        HttpClient httpClient = HttpClientBuilder.create()
                .setProxy(new HttpHost(appBizConfig.getProxyHost(), appBizConfig.getProxyPort()))
                .setDefaultRequestConfig(config)
                .disableRedirectHandling()
                .disableCookieManagement().build();
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory();
        requestFactory.setHttpClient(httpClient);
        requestFactory.setConnectTimeout(3000);
        requestFactory.setConnectionRequestTimeout(3000);
        return addResponseLimit(RestClient.builder().requestFactory(requestFactory));
    }


    @Nonnull
    public RestClient.Builder buildRestClient(Integer timeoutSeconds) {
        int boundedTimeout = boundedTimeoutSeconds(timeoutSeconds);
        RequestConfig config = RequestConfig.custom()
                .setResponseTimeout(Timeout.ofSeconds(boundedTimeout))
                .build();

        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(ModelEndpointPolicy.publicAddressResolver(allowPrivateModelAddresses))
                .setMaxConnTotal(50)
                .setMaxConnPerRoute(10)
                .build();
        HttpClient httpClient = HttpClientBuilder.create()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(config)
                .disableRedirectHandling()
                .disableCookieManagement().build();
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory();
        requestFactory.setHttpClient(httpClient);
        requestFactory.setConnectTimeout(5000);
        requestFactory.setConnectionRequestTimeout(5000);
        return addResponseLimit(RestClient.builder().requestFactory(requestFactory));
    }

    static int boundedTimeoutSeconds(Integer timeoutSeconds) {
        return timeoutSeconds == null
                ? DEFAULT_TIMEOUT_SECONDS
                : Math.max(1, Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS));
    }

    public void validateModelEndpoint(String baseUrl) {
        ModelEndpointPolicy.validateBaseUrl(
                baseUrl, allowPrivateModelAddresses, allowInsecureModelHttp);
    }

    private static RestClient.Builder addResponseLimit(RestClient.Builder builder) {
        return builder.requestInterceptor((request, body, execution) -> {
            ClientHttpResponse response = execution.execute(request, body);
            long contentLength = response.getHeaders().getContentLength();
            if (contentLength > MAX_RESPONSE_BYTES) {
                response.close();
                throw new IOException("模型服务响应超过大小限制");
            }
            return limitResponseBody(response);
        });
    }

    static ClientHttpResponse limitResponseBody(ClientHttpResponse response) {
        return new ClientHttpResponse() {
            private InputStream boundedBody;

            @Override
            public org.springframework.http.HttpStatusCode getStatusCode() throws IOException {
                return response.getStatusCode();
            }

            @Override
            public String getStatusText() throws IOException {
                return response.getStatusText();
            }

            @Override
            public org.springframework.http.HttpHeaders getHeaders() {
                return response.getHeaders();
            }

            @Override
            public InputStream getBody() throws IOException {
                if (boundedBody == null) {
                    boundedBody = new FilterInputStream(response.getBody()) {
                        private int bytesRead;

                        @Override
                        public int read() throws IOException {
                            int value = super.read();
                            if (value != -1 && ++bytesRead > MAX_RESPONSE_BYTES) {
                                throw new IOException("模型服务响应超过大小限制");
                            }
                            return value;
                        }

                        @Override
                        public int read(byte[] bytes, int offset, int length) throws IOException {
                            int allowedLength = Math.min(length, MAX_RESPONSE_BYTES - bytesRead + 1);
                            int read = super.read(bytes, offset, allowedLength);
                            if (read > 0) {
                                bytesRead += read;
                                if (bytesRead > MAX_RESPONSE_BYTES) {
                                    throw new IOException("模型服务响应超过大小限制");
                                }
                            }
                            return read;
                        }
                    };
                }
                return boundedBody;
            }

            @Override
            public void close() {
                response.close();
            }
        };
    }

    /**
     * 获取 AI 配置
     * 适配分隔符为[ . or - ]
     *
     * @param func 函数
     * @param key  钥匙
     * @return {@link R }
     */
    public <R> R getAiConfig(Function<String, R> func, String key) {
        R result = func.apply(key);
        if (result != null) {
            return result;
        }
        if (key.contains(".")) {
            return func.apply(key.replace(".", "-"));
        } else if (key.contains("-")) {
            return func.apply(key.replace("-", "."));
        }
        return null;
    }
}
