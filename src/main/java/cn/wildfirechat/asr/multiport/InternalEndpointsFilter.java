package cn.wildfirechat.asr.multiport;


import cn.wildfirechat.asr.jpa.Application;
import cn.wildfirechat.asr.jpa.ApplicationRepository;
import org.apache.catalina.connector.RequestFacade;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

import javax.servlet.*;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class InternalEndpointsFilter implements Filter {
    private static final Logger LOG= LoggerFactory.getLogger(InternalEndpointsFilter.class);
    private final int adminPort;
    private final String adminPathPrefix;
    private final ApplicationRepository applicationRepository;
    private final boolean needSignature;

    private static final String BAD_REQUEST = String.format("{\"code\":%d,\"error\":true,\"errorMessage\":\"%s\"}",
            HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.getReasonPhrase());

    private static final String UNAUTHORIZED = String.format("{\"code\":%d,\"error\":true,\"errorMessage\":\"%s\"}",
            HttpStatus.UNAUTHORIZED.value(), HttpStatus.UNAUTHORIZED.getReasonPhrase());

    private static final long EXPIRATION_TIME = TimeUnit.MINUTES.toMillis(5); // 5分钟过期时间

    private ConcurrentHashMap<String, Long> nonceMap = new ConcurrentHashMap<>();

    private boolean isValidNonce(String nonce) {
        long currentTime = System.currentTimeMillis();
        // 定期清理过期的 nonce
        nonceMap.entrySet().removeIf(entry -> (currentTime - entry.getValue()) > EXPIRATION_TIME);

        Long existingTimestamp = nonceMap.get(nonce);

        // 如果已经存在且在5分钟内，则认为是重复请求
        if (existingTimestamp != null && (currentTime - existingTimestamp) < EXPIRATION_TIME) {
            return false;
        }

        // 更新或插入新的 nonce 和当前时间戳
        nonceMap.put(nonce, currentTime);
        return true;
    }

    public InternalEndpointsFilter(int adminPort, String adminPathPrefix, ApplicationRepository applicationRepository, boolean needSignature) {
        this.adminPort = adminPort;
        this.adminPathPrefix = adminPathPrefix;
        this.applicationRepository = applicationRepository;
        this.needSignature = needSignature;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        if (servletRequest.getLocalPort() != adminPort
            && !((RequestFacade) servletRequest).getRequestURI().startsWith("/api")) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }
        boolean isAdminAPI = ((RequestFacade) servletRequest).getRequestURI().startsWith(adminPathPrefix);
        boolean isAdminPort = servletRequest.getLocalPort() == adminPort;
        if(isAdminPort && !isAdminAPI) {
            if(((RequestFacade) servletRequest).getRequestURI().equals("/") || ((RequestFacade) servletRequest).getRequestURI().equals("/index.html") || ((RequestFacade) servletRequest).getRequestURI().startsWith("/assets/")) {
                isAdminAPI = true;
            }
        }

        boolean isExternalAPI = !isAdminAPI;
        boolean isExternalPort = !isAdminPort;

        boolean isExternalHello = isExternalAPI && "/api/hello".equals(((RequestFacade) servletRequest).getRequestURI());

        if((isAdminAPI && isAdminPort) || (isExternalAPI && isExternalPort)) {
            if(needSignature && (isExternalAPI && !isExternalHello)) {
                String nonce = ((RequestFacade) servletRequest).getHeader("nonce");
                if (!StringUtils.hasText(nonce)) {
                    nonce = ((RequestFacade) servletRequest).getHeader("Nonce");
                }
                String timestamp = ((RequestFacade) servletRequest).getHeader("timestamp");
                if (!StringUtils.hasText(timestamp)) {
                    timestamp = ((RequestFacade) servletRequest).getHeader("Timestamp");
                }
                String sign = ((RequestFacade) servletRequest).getHeader("sign");
                if (!StringUtils.hasText(sign)) {
                    sign = ((RequestFacade) servletRequest).getHeader("Sign");
                }

                String appId = ((RequestFacade) servletRequest).getHeader("x-app-id");
                if (!StringUtils.hasText(sign)) {
                    appId = ((RequestFacade) servletRequest).getHeader("X-app-id");
                }

                if (StringUtils.hasText(nonce) && StringUtils.hasText(timestamp) && StringUtils.hasText(sign) && StringUtils.hasText(appId)) {
                    if (isValidNonce(nonce)) {
                        long ts = Long.parseLong(timestamp);
                        if(StringUtils.hasText(appId)) {
                            if(ts > 0 && System.currentTimeMillis() - ts < EXPIRATION_TIME) {
                                Optional<Application> optionalEntity = applicationRepository.findById(appId);
                                if(optionalEntity.isPresent()) {
                                    String secret = optionalEntity.get().secret;
                                    String str = nonce + "|" + secret + "|" + timestamp;
                                    String localSign = DigestUtils.sha1Hex(str);
                                    if(localSign.equals(sign)) {
                                        servletRequest.setAttribute("app_id", appId);
                                        filterChain.doFilter(servletRequest, servletResponse);
                                        return;
                                    } else {
                                        LOG.error("sign for application {} is incorrect", appId);
                                    }
                                } else {
                                    LOG.error("application {} is not exist", appId);
                                }
                            } else {
                                LOG.error("timestamp header miss or expired");
                            }
                        } else {
                            LOG.error("request miss x-app-id header");
                        }
                    } else {
                        LOG.error("invalid nonce");
                    }
                } else {
                    LOG.error("request miss auth headers");
                }
                ((HttpServletResponse) servletResponse).setStatus(HttpStatus.UNAUTHORIZED.value());
                servletResponse.getOutputStream().write(UNAUTHORIZED.getBytes(StandardCharsets.UTF_8));
                servletResponse.getOutputStream().close();
            } else {
                filterChain.doFilter(servletRequest, servletResponse);
            }
        } else {
            ((HttpServletResponse) servletResponse).setStatus(HttpStatus.BAD_REQUEST.value());
            servletResponse.getOutputStream().write(BAD_REQUEST.getBytes(StandardCharsets.UTF_8));
            servletResponse.getOutputStream().close();
        }
    }
}
