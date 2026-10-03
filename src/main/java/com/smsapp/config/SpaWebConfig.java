package com.smsapp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Serves the SPA's {@code index.html} for any client-side route that has no
 * matching server route or static file of its own.
 *
 * <p>The frontend's build output lives in {@code src/main/resources/static}
 * (copied there by the combined build -- see the project's Containerfile),
 * and React Router handles navigation entirely in the browser. A fresh
 * request for a deep link such as {@code /students/12} has no corresponding
 * static file, so without this fallback it would 404 instead of loading the
 * app shell and letting the client-side router take over.
 *
 * <p>This intentionally does NOT use an {@code @RequestMapping} pattern like
 * {@code "/**}{@code /{path:[^.]*}"} -- Spring 6's default PathPatternParser
 * rejects any pattern segment after a {@code **} wildcard ("No more pattern
 * data allowed after {*...} or ** pattern element"), which broke the first
 * version of this fallback. A custom {@link PathResourceResolver} sidesteps
 * that entirely: it runs inside the existing static-resource handler chain
 * (ordered after API controllers, so {@code /api/**} is untouched) and falls
 * back to {@code index.html} only when the requested path does not resolve
 * to a real file under {@code static/}.
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
            .resourceChain(true)
            .addResolver(new PathResourceResolver() {
                @Override
                protected Resource getResource(String resourcePath, Resource location) throws IOException {
                    Resource requested = location.createRelative(resourcePath);
                    return requested.exists() && requested.isReadable()
                        ? requested
                        : new ClassPathResource("/static/index.html");
                }
            });
    }
}
