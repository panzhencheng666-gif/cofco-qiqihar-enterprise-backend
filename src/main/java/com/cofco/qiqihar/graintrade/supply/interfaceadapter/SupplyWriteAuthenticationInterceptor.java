package com.cofco.qiqihar.graintrade.supply.interfaceadapter;
import com.cofco.qiqihar.graintrade.shared.application.AuthenticationRequiredException;import com.cofco.qiqihar.graintrade.supply.application.CurrentActor;
import jakarta.servlet.http.HttpServletRequest;import jakarta.servlet.http.HttpServletResponse;import org.springframework.context.annotation.Configuration;import org.springframework.web.servlet.HandlerInterceptor;import org.springframework.web.servlet.config.annotation.InterceptorRegistry;import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
@Configuration(proxyBeanMethods=false) public class SupplyWriteAuthenticationInterceptor implements HandlerInterceptor,WebMvcConfigurer{
 private final CurrentActor actor;public SupplyWriteAuthenticationInterceptor(CurrentActor actor){this.actor=actor;}public void addInterceptors(InterceptorRegistry r){r.addInterceptor(this).addPathPatterns("/api/v1/supply-accounts/**");}public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object h){if(!req.getMethod().equals("GET")&&actor.currentActor().isEmpty())throw new AuthenticationRequiredException();return true;}
}
