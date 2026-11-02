package com.ayrotek.agent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Yerel panel ayrı bir portta ve yalnızca 127.0.0.1'de açılır. Ajan portu koordinatöre ve diğer kurumlara açık olduğu için
 * panel uçları orada hiç yoktur; panel portunda da yalnızca panel vardır. Kurum yöneticisi panele makinenin kendisinden
 * veya SSH tüneliyle ulaşır.
 */
@Configuration
public class PanelAccessConfig {

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    @Value("${agent.panel.port:8094}")
    private int panelPort;

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> panelConnector() {
        return factory -> {
            if (panelPort <= 0) {
                return;
            }
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(panelPort);
            connector.setProperty("address", "127.0.0.1");
            factory.addAdditionalTomcatConnectors(connector);
        };
    }

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> panelGuard() {
        FilterRegistrationBean<OncePerRequestFilter> bean = new FilterRegistrationBean<>(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String path = request.getRequestURI();
                boolean onPanelPort = request.getLocalPort() == panelPort;
                if (onPanelPort && (path.equals("/") || path.equals("/panel") || path.equals("/panel/"))) {
                    response.sendRedirect("/panel/index.html");
                    return;
                }
                boolean panelPath = path.startsWith("/panel/") || path.startsWith("/api/v1/panel/");
                if (panelPath != onPanelPort) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
                if (onPanelPort) {
                    // DNS yeniden bağlama: başka bir site adıyla gelen istek panele ulaşamaz
                    String host = request.getServerName();
                    if (host == null || !LOCAL_HOSTS.contains(host.toLowerCase())) {
                        response.sendError(HttpServletResponse.SC_FORBIDDEN);
                        return;
                    }
                    // Başka bir sitenin tarayıcı üzerinden değişiklik yapmasını engeller: bu başlık ön kontrol ister ve CORS kapalıdır
                    if (!"GET".equals(request.getMethod()) && !"1".equals(request.getHeader("X-Agent-Panel"))) {
                        response.sendError(HttpServletResponse.SC_FORBIDDEN);
                        return;
                    }
                    response.setHeader("X-Frame-Options", "DENY");
                    response.setHeader("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self'");
                    response.setHeader("Cache-Control", "no-store");
                }
                chain.doFilter(request, response);
            }
        });
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }
}
