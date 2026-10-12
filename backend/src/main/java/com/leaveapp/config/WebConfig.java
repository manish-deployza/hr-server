package com.leaveapp.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Servlet context: scans only {@code com.leaveapp.rest} for controllers, so
 * DispatcherServlet's handler mapping (which does not walk up to the root context)
 * finds them.
 */
@Configuration
@EnableWebMvc
@ComponentScan(basePackages = "com.leaveapp.rest")
public class WebConfig implements WebMvcConfigurer {

    /* Public Methods */

    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // AckData and similar marker DTOs are intentionally field-less ({}); without this,
        // Jackson can't build a serializer for them and every ack-only endpoint 500s.
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        converters.add(new MappingJackson2HttpMessageConverter(mapper));
    }

    // Servlet 3.0 native multipart (AppInitializer.getMultipartConfigElement()) still
    // needs this bean, by name, before Spring will bind a MultipartFile argument -- no
    // Apache Commons FileUpload dependency required.
    @Bean
    public MultipartResolver multipartResolver() {
        return new StandardServletMultipartResolver();
    }
}
