package com.redtourism.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 所有 /api/ 响应统一禁用缓存。
 * 评价删除等写操作历史上使用 GET，浏览器会按启发式规则缓存响应，
 * 出现“删除后过一会又看到原评价”的假象，这里从响应头层面彻底规避。
 */
@Configuration
public class NoCacheApiFilterConfig {

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> noCacheApiFilter() {
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain filterChain) throws ServletException, IOException {
                response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
                response.setHeader("Pragma", "no-cache");
                response.setDateHeader("Expires", 0);
                filterChain.doFilter(request, response);
            }
        });
        registration.addUrlPatterns("/api/*");
        registration.setOrder(0);
        return registration;
    }
}
