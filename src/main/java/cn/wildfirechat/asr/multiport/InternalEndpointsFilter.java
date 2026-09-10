package cn.wildfirechat.asr.multiport;


import cn.wildfirechat.asr.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class InternalEndpointsFilter implements Filter {
    private static final Logger LOG= LoggerFactory.getLogger(InternalEndpointsFilter.class);

    // 客户端在这个 HTTP header 中带上从 IM 服务获取的认证码
    public static final String HEADER_AUTH_CODE = "authCode";
    // 认证码校验通过后，用户 ID 保存在这个请求属性中
    public static final String ATTR_USER_ID = "user_id";

    private final int adminPort;
    private final String adminPathPrefix;
    private final AuthService authService;
    private final boolean needAuth;

    private static final String BAD_REQUEST = String.format("{\"code\":%d,\"error\":true,\"errorMessage\":\"%s\"}",
            HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.getReasonPhrase());

    private static final String UNAUTHORIZED = String.format("{\"code\":%d,\"error\":true,\"errorMessage\":\"%s\"}",
            HttpStatus.UNAUTHORIZED.value(), HttpStatus.UNAUTHORIZED.getReasonPhrase());

    public InternalEndpointsFilter(int adminPort, String adminPathPrefix, AuthService authService, boolean needAuth) {
        this.adminPort = adminPort;
        this.adminPathPrefix = adminPathPrefix;
        this.authService = authService;
        this.needAuth = needAuth;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        String uri = request.getRequestURI();
        boolean isAdminPort = request.getLocalPort() == adminPort;
        if (!isAdminPort && !uri.startsWith("/api")) {
            filterChain.doFilter(request, response);
            return;
        }
        boolean isAdminAPI = uri.startsWith(adminPathPrefix);
        if(isAdminPort && !isAdminAPI) {
            if(uri.equals("/") || uri.equals("/index.html") || uri.startsWith("/assets/")) {
                isAdminAPI = true;
            }
        }

        boolean isExternalAPI = !isAdminAPI;
        boolean isExternalPort = !isAdminPort;

        if((isAdminAPI && isAdminPort) || (isExternalAPI && isExternalPort)) {
            boolean isExternalHello = isExternalAPI && "/api/hello".equals(uri);
            boolean isPreflight = "OPTIONS".equals(request.getMethod());
            if(needAuth && isExternalAPI && !isExternalHello && !isPreflight) {
                // 客户端接口（包括 WebSocket 握手请求）都需要带上认证码
                String userId = authService.verifyAuthCode(request.getHeader(HEADER_AUTH_CODE));
                if (userId == null) {
                    LOG.error("request {} miss authCode header or authCode is invalid", uri);
                    writeResponse(response, HttpStatus.UNAUTHORIZED, UNAUTHORIZED);
                    return;
                }
                request.setAttribute(ATTR_USER_ID, userId);
            }
            filterChain.doFilter(request, response);
        } else {
            writeResponse(response, HttpStatus.BAD_REQUEST, BAD_REQUEST);
        }
    }

    private static void writeResponse(HttpServletResponse response, HttpStatus status, String body) throws IOException {
        response.setStatus(status.value());
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        response.getOutputStream().close();
    }
}
