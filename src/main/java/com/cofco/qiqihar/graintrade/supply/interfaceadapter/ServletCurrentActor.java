package com.cofco.qiqihar.graintrade.supply.interfaceadapter;
import com.cofco.qiqihar.graintrade.supply.application.AuthenticatedActor;
import com.cofco.qiqihar.graintrade.supply.application.CurrentActor;
import java.security.Principal;import java.util.Optional;
import org.springframework.stereotype.Component;import org.springframework.web.context.request.RequestContextHolder;import org.springframework.web.context.request.ServletRequestAttributes;
@Component("supplyServletCurrentActor") public class ServletCurrentActor implements CurrentActor{
 public Optional<AuthenticatedActor> currentActor(){if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a))return Optional.empty();Principal p=a.getRequest().getUserPrincipal();return p==null||p.getName()==null||p.getName().isBlank()?Optional.empty():Optional.of(new AuthenticatedActor(p.getName()));}
}
