package com.cofco.qiqihar.graintrade.logistics.interfaceadapter;
import com.cofco.qiqihar.graintrade.logistics.application.CurrentActor;
import com.cofco.qiqihar.graintrade.shared.application.AuthenticationRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
@Configuration(proxyBeanMethods=false)
public class LogisticsWriteAuthenticationInterceptor implements HandlerInterceptor,WebMvcConfigurer{
 private final CurrentActor actor; public LogisticsWriteAuthenticationInterceptor(CurrentActor actor){this.actor=actor;}
 public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(this).addPathPatterns("/api/v1/logistics-records/**");}
 public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler){if(!request.getMethod().equals("GET")&&actor.currentActor().isEmpty())throw new AuthenticationRequiredException();return true;}
}
